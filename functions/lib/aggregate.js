'use strict';
const { buildEvents } = require('./events');
/**
 * The aggregator's core, free of Firebase so it can be tested on its own.
 *
 * Each source pulls what it can and returns a partial summary. A source that fails keeps the last
 * data it pulled, so the tablet shows "last good numbers, updated X ago" with the error beside it,
 * never a blank. The result is one summary in the shape the tablet's Hub.parse reads.
 */

const REFRESH_GAP_MS = 10 * 60 * 1000;
const METRIC_KEYS = ['revenue', 'downloads', 'users', 'ads', 'impressions', 'ecpm', 'crashes', 'reviews', 'rating', 'uptime', 'bounces'];

/** True when the last refresh was at least ten minutes ago (or the clock went backwards). */
function shouldRefresh(lastAt, now) {
  return !lastAt || now - lastAt >= REFRESH_GAP_MS || now < lastAt;
}

/**
 * Run every source. The App Store goes first: its app list (Apple id, bundle id, name) is what the
 * other sources match their apps to. The rest run side by side.
 *
 * @param sources  [{ key, label, configured(cfg), pull(ctx) }]
 * @param prev     the last state: { sources: { key: { ok, at, error, data } } }
 */
async function runSources(sources, prev, cfg, now, deps = {}) {
  const state = { sources: {} };
  const prevSources = (prev && prev.sources) || {};
  const ctx = { cfg, now, deps, apps: [], prev: prevSources };

  async function one(src) {
    const old = prevSources[src.key];
    if (!src.configured(cfg)) {
      state.sources[src.key] = { label: src.label, connected: false, ok: false, at: 0, error: '', data: null };
      return;
    }
    try {
      const data = await src.pull(ctx);
      state.sources[src.key] = { label: src.label, connected: true, ok: true, at: now, error: '', data };
    } catch (e) {
      state.sources[src.key] = {
        label: src.label, connected: true, ok: false,
        at: old && old.at ? old.at : 0,
        error: shortError(e),
        data: old ? old.data : null,
      };
    }
  }

  const first = sources.filter((s) => s.first);
  for (const s of first) {
    await one(s);
    const d = state.sources[s.key].data;
    if (d && d.apps) ctx.apps.push(...d.apps);
  }
  await Promise.all(sources.filter((s) => !s.first).map(one));
  return state;
}

function shortError(e) {
  const m = (e && e.message) || String(e);
  return m.replace(/Bearer\s+\S+/g, 'Bearer …').slice(0, 200);
}

/** Combine every source's data (fresh or last good) into the summary the tablet reads. */
function buildSummary(state, cfg, now) {
  const apps = new Map();
  const out = { updatedAt: now, apps: [], sources: {}, reviews: [], costs: [], repos: [], sites: [], events: [] };
  const extraEvents = [];
  let sitesAt = 0;

  const order = Object.keys(state.sources);
  for (const key of order) {
    const s = state.sources[key];
    out.sources[key] = { label: s.label, connected: s.connected, ok: s.ok, at: s.at, error: s.error };
    const d = s.data;
    if (!d) continue;
    for (const a of d.apps || []) {
      const had = apps.get(a.id) || { id: a.id, metrics: {} };
      apps.set(a.id, mergeApp(had, a));
    }
    for (const [appId, ms] of Object.entries(d.metrics || {})) {
      const had = apps.get(appId);
      if (!had) continue; // a metric for an app no source listed: skip it, don't invent a name
      for (const [k, v] of Object.entries(ms)) if (METRIC_KEYS.includes(k) && v) had.metrics[k] = clean(v);
    }
    if (d.payout) out.payout = d.payout;
    if (d.reviews) out.reviews.push(...d.reviews);
    if (d.costs) out.costs.push(...d.costs);
    if (d.repos) out.repos.push(...d.repos);
    if (d.sites) { out.sites.push(...d.sites); sitesAt = Math.max(sitesAt, s.at || 0); }
    if (d.events) extraEvents.push(...d.events);
  }
  // 2.0.2: the hub calendar
  out.events = buildEvents(out, extraEvents, cfg, now, sitesAt);

  // the newest review per app is the one its panel shows; the inbox keeps the newest 50
  out.reviews.sort((a, b) => (b.at || 0) - (a.at || 0));
  out.reviews = out.reviews.slice(0, 50);
  for (const a of apps.values()) {
    if (!a.latestReview) {
      const r = out.reviews.find((x) => x.appId === a.id);
      if (r) a.latestReview = r;
    }
    const reviewsToday = out.reviews.filter((x) => x.appId === a.id && now - (x.at || 0) < 86400000).length;
    if (!a.metrics.reviews && out.reviews.some((x) => x.appId === a.id)) {
      a.metrics.reviews = { today: reviewsToday, week: out.reviews.filter((x) => x.appId === a.id && now - (x.at || 0) < 7 * 86400000).length };
    }
    decideStatus(a, out.sites);
  }
  // store apps first (by name), then the sites
  out.apps = [...apps.values()].sort((x, y) => (x.kind === 'site') - (y.kind === 'site') || String(x.name).localeCompare(String(y.name)));
  return out;
}

function mergeApp(had, a) {
  const m = { ...had };
  for (const [k, v] of Object.entries(a)) {
    if (k === 'metrics') continue;
    if (k === 'crashes' && Array.isArray(v)) { m.crashes = [...(m.crashes || []), ...v]; continue; }
    if (v !== undefined && v !== null && v !== '' && (m[k] === undefined || m[k] === '' || k !== 'name')) m[k] = v;
  }
  if (a.metrics) for (const [k, v] of Object.entries(a.metrics)) if (v) m.metrics[k] = clean(v);
  return m;
}

function clean(v) {
  const o = {};
  if (num(v.today)) o.today = round(v.today);
  if (num(v.week)) o.week = round(v.week);
  if (Array.isArray(v.series)) o.series = v.series.map((x) => (num(x) ? round(x) : 0));
  if (v.currency) o.currency = v.currency;
  return o;
}
const num = (x) => typeof x === 'number' && Number.isFinite(x);
const round = (x) => Math.round(x * 10000) / 10000;

/**
 * An app's glow: errors when its site is down, it was rejected in review or a crash group is new;
 * attention when it's waiting on Apple, a certificate or domain runs out within 14 days, or uptime
 * slipped under 99.5%. Fine otherwise. Left unset when nothing is known, so the tablet shows grey.
 */
function decideStatus(a, sites) {
  const notes = { errors: [], attention: [] };
  const site = sites.find((s) => s.appId === a.id);
  if (site) {
    if (!site.up) notes.errors.push(`${site.host} is down`);
    if (site.sslDays != null && site.sslDays < 0) notes.errors.push('SSL certificate expired');
    else if (site.sslDays != null && site.sslDays <= 14) notes.attention.push(`SSL expires in ${site.sslDays} days`);
    if (site.domainDays != null && site.domainDays <= 14) notes.attention.push(`domain renews in ${site.domainDays} days`);
  }
  const st = a.review && a.review.state;
  if (['REJECTED', 'METADATA_REJECTED', 'INVALID_BINARY'].includes(st)) notes.errors.push('rejected in review');
  else if (['WAITING_FOR_REVIEW', 'IN_REVIEW', 'PENDING_DEVELOPER_RELEASE'].includes(st)) notes.attention.push('waiting on App Store review');
  const fresh = (a.crashes || []).filter((c) => c.fresh);
  if (fresh.length) notes.errors.push(fresh.length === 1 ? 'a new crash' : `${fresh.length} new crashes`);
  const up = a.metrics.uptime && a.metrics.uptime.today;
  if (num(up) && up < 99.5 && (!site || site.up)) notes.attention.push(`uptime ${up.toFixed(1)}% today`);
  const b = a.metrics.bounces && a.metrics.bounces.today;
  if (num(b) && b >= 5) notes.attention.push(`${b} email bounces today`);
  if (notes.errors.length) { a.status = 'errors'; a.statusNote = cap(notes.errors.concat(notes.attention).join(' · ')); }
  else if (notes.attention.length) { a.status = 'attention'; a.statusNote = cap(notes.attention.join(' · ')); }
  else if (site || a.review || Object.keys(a.metrics).length) { a.status = a.status || 'fine'; }
}
// capitalise the note, but never a host name (whiteswan.co.uk stays lower case)
const cap = (s) => (/^[^\s]*\.[a-z]/.test(s) ? s : s.charAt(0).toUpperCase() + s.slice(1));

/**
 * One refresh, start to end: pull, merge, save. [store] reads and writes the state and summary.
 * Two refreshes never overlap: one that started under two minutes ago holds the lock.
 */
async function refresh({ store, sources, cfg, now, deps, force = false }) {
  const prev = (await store.getState()) || {};
  if (!force && !shouldRefresh(prev.lastRefresh || 0, now)) return { ran: false, summary: await store.getSummary() };
  if (prev.lockUntil && prev.lockUntil > now) return { ran: false, summary: await store.getSummary() };
  await store.setState({ ...prev, lockUntil: now + 120000 });
  try {
    const st = await runSources(sources, prev, cfg, now, deps);
    const summary = buildSummary(st, cfg, now);
    await store.setState({ sources: st.sources, lastRefresh: now, lockUntil: 0 });
    await store.setSummary(summary);
    return { ran: true, summary };
  } catch (e) {
    await store.setState({ ...prev, lockUntil: 0 });
    throw e;
  }
}

module.exports = { shouldRefresh, runSources, buildSummary, refresh, REFRESH_GAP_MS, METRIC_KEYS };
