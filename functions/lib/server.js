'use strict';
/**
 * The tablet's two calls, free of Firebase so they can be tested:
 *   GET  /summary        the latest summary (with ?wake=1 it refreshes first, unless it did so
 *                        under ten minutes ago)
 *   POST /reply          { reviewId, appId, text }: reply to an App Store review
 * Both need "Authorization: Bearer <HUB_TOKEN>".
 */
const crypto = require('crypto');
const agg = require('./aggregate');
const appstore = require('./sources/appstore');

function sameToken(given, want) {
  if (!want) return false;
  const a = crypto.createHash('sha256').update(String(given || '')).digest();
  const b = crypto.createHash('sha256').update(want).digest();
  return crypto.timingSafeEqual(a, b);
}

/** "name|url" pairs joined by tabs, URL-encoded; only http(s) addresses, at most 10. */
function parseSitesHeader(v) {
  if (!v) return [];
  let text;
  try { text = decodeURIComponent(String(v).replace(/\+/g, ' ')); } catch (_) { return []; }
  return text.split('\t').map((x) => {
    const i = x.indexOf('|');
    if (i < 1) return null;
    const name = x.slice(0, i).trim().slice(0, 60); const url = x.slice(i + 1).trim();
    try { const u = new URL(url); if (u.protocol !== 'https:' && u.protocol !== 'http:') return null; } catch (_) { return null; }
    return { name, url };
  }).filter(Boolean).slice(0, 10);
}

/** Handle one request. Returns { status, body }. */
async function handle({ method, path, query, headers, body }, { store, sources, cfg, now, deps }) {
  headers = headers || {};
  const auth = String(headers.authorization || headers.Authorization || '');
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  if (!cfg.hubToken) return { status: 503, body: { error: 'HUB_TOKEN is not set on the server' } };
  if (!sameToken(token, cfg.hubToken)) return { status: 401, body: { error: 'bad token' } };
  const p = String(path || '').replace(/\/+$/, '').split('/').pop();

  if (method === 'GET' && p === 'summary') {
    // the tablet's own site list counts when the server's config lists none
    const tabletSites = parseSitesHeader(headers['x-hub-sites'] || headers['X-Hub-Sites']);
    if (tabletSites.length && !(cfg.sites && cfg.sites.length)) cfg = { ...cfg, sites: tabletSites };
    let summary = await store.getSummary();
    if (query && (query.wake === '1' || !summary)) {
      try {
        const r = await agg.refresh({ store, sources, cfg, now, deps, force: !summary });
        summary = r.summary || summary;
      } catch (e) {
        if (!summary) return { status: 502, body: { error: 'refresh failed' } };
      }
    }
    return { status: 200, body: summary || { updatedAt: 0, apps: [] } };
  }

  if (method === 'POST' && p === 'reply') {
    const b = typeof body === 'string' ? JSON.parse(body || '{}') : body || {};
    const text = String(b.text || '').trim();
    if (!b.reviewId || !text) return { status: 400, body: { error: 'reviewId and text are needed' } };
    if (!sources.find((s) => s.key === 'appstore').configured(cfg)) return { status: 409, body: { error: 'App Store Connect is not set up' } };
    try {
      await (deps.reply || appstore.reply)(cfg, { reviewId: b.reviewId, text }, deps);
    } catch (e) {
      return { status: 502, body: { error: 'App Store Connect refused the reply' } };
    }
    // show it straight away, before the next refresh
    const s = await store.getSummary();
    if (s) {
      const fix = (r) => (r && r.id === b.reviewId ? { ...r, reply: text } : r);
      s.reviews = (s.reviews || []).map(fix);
      s.apps = (s.apps || []).map((a) => ({ ...a, latestReview: fix(a.latestReview) }));
      await store.setSummary(s);
    }
    return { status: 200, body: { ok: true } };
  }
  return { status: 404, body: { error: 'not found' } };
}

module.exports = { handle, sameToken, parseSitesHeader };
