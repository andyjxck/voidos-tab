'use strict';
/**
 * App Store Connect: the app list (names, bundle ids, icons) for the drawer and status board, the
 * review status of each app's newest version, customer reviews (and replies), and from the Sales
 * and Trends reports: downloads and proceeds, up to a year of days; Finance Reports: a fiscal
 * month's reported proceeds (2.0.2: no payout estimate, and no payment date or amount is made up).
 *
 * Phase 1 needs only the app list. The rest switch on with the same key; sales need the vendor
 * number too (App Store Connect › Payments and Financial Reports, top left).
 */
const zlib = require('zlib');
const { request } = require('../http');
const { ascToken } = require('../ascJwt');
const { DAY, ymd, lastDays, HISTORY_DAYS } = require('../dates');
const { versionEvents, logReviews, reviewLogEvents } = require('../events');

const API = 'https://api.appstoreconnect.apple.com';

// Product Type Identifiers that count as a download (first-time installs, not updates or IAPs)
// https://developer.apple.com/help/app-store-connect/reference/product-type-identifiers
const DOWNLOAD_TYPES = new Set(['1', '1F', '1T', 'F1', '1E', '1EP', '1EU']);

function cfgOf(cfg) { return cfg.appstore || {}; }

const source = {
  key: 'appstore',
  label: 'App Store Connect',
  first: true,
  configured: (cfg) => !!(cfgOf(cfg).keyId && cfgOf(cfg).issuerId && cfgOf(cfg).privateKey),
  async pull(ctx) {
    const c = cfgOf(ctx.cfg);
    const f = ctx.deps.fetch;
    const token = ascToken(c, ctx.now);
    const get = (path) => request('App Store Connect', API + path, { headers: { Authorization: `Bearer ${token}` }, fetchImpl: f });

    const list = await get('/v1/apps?limit=200&fields[apps]=name,bundleId,sku,primaryLocale');
    const hidden = new Set((c.hide || []).map(String));
    const raw = (list.data || []).filter((a) => !hidden.has(a.id) && !hidden.has(a.attributes.bundleId));
    const apps = [];
    const metrics = {};
    const reviews = [];
    const listings = new Map();
    const versionsOf = new Map();

    await Promise.all(raw.map(async (a) => {
      const id = `asc:${a.id}`;
      const app = {
        id, name: a.attributes.name, kind: 'app', platform: 'ios', bundleId: a.attributes.bundleId, appleId: a.id,
        consoleUrl: `https://appstoreconnect.apple.com/apps/${a.id}/distribution`,
        url: `https://apps.apple.com/app/id${a.id}`,
      };
      apps.push(app);
      // the icon: the public App Store listing has it for live apps (the home storefront first,
      // then the US one for apps not sold here); apps not live yet use their newest build's icon
      for (const country of [c.country || 'gb', 'us']) {
        if (app.icon) break;
        try {
          const look = await request('iTunes lookup', `https://itunes.apple.com/lookup?id=${a.id}&country=${country}`, { fetchImpl: f, timeoutMs: 8000 });
          const r = look && look.results && look.results[0];
          if (r) {
            app.icon = r.artworkUrl512 || r.artworkUrl100 || '';
            if (!listings.has(id)) listings.set(id, { version: r.version || '', currentVersionReleaseDate: r.currentVersionReleaseDate || '', releaseDate: r.releaseDate || '' });
            if (r.averageUserRating) metrics[id] = { ...(metrics[id] || {}), rating: { today: r.averageUserRating } };
          }
        } catch (_) { /* try the next storefront */ }
      }
      if (!app.icon) {
        try {
          const b = await get(`/v1/builds?filter[app]=${a.id}&sort=-uploadedDate&limit=1&fields[builds]=iconAssetToken`);
          const t = b && b.data && b.data[0] && b.data[0].attributes && b.data[0].attributes.iconAssetToken;
          if (t && t.templateUrl) app.icon = t.templateUrl.replace('{w}', '512').replace('{h}', '512').replace('{f}', 'png');
        } catch (_) { /* no build icon: the tablet falls back to initials */ }
      }
      // review status of the newest version, and (2.0.2) the dated versions for the calendar
      try {
        const v = await get(`/v1/apps/${a.id}/appStoreVersions?limit=8&fields[appStoreVersions]=versionString,appStoreState,appVersionState,createdDate,earliestReleaseDate,releaseType,appStoreVersionPhasedRelease&include=appStoreVersionPhasedRelease&fields[appStoreVersionPhasedReleases]=phasedReleaseState,startDate,currentDayNumber`);
        const phased = new Map((v.included || []).filter((x) => x.type === 'appStoreVersionPhasedReleases').map((x) => [x.id, x.attributes || {}]));
        const rows = (v.data || []).map((row) => {
          const rel = row.relationships && row.relationships.appStoreVersionPhasedRelease && row.relationships.appStoreVersionPhasedRelease.data;
          return { ...row, phased: rel ? phased.get(rel.id) : undefined };
        }).sort((x, y) => (Date.parse((y.attributes || {}).createdDate || '') || 0) - (Date.parse((x.attributes || {}).createdDate || '') || 0));
        const n = rows[0];
        if (n) {
          app.review = {
            state: n.attributes.appVersionState || n.attributes.appStoreState || '',
            version: n.attributes.versionString || '',
            since: Date.parse(n.attributes.createdDate || '') || 0,
          };
        }
        versionsOf.set(id, rows);
      } catch (_) { /* leave the review state unknown */ }
      // the newest customer reviews, with any reply already sent
      try {
        const r = await get(`/v1/apps/${a.id}/customerReviews?limit=20&sort=-createdDate&include=response&fields[customerReviewResponses]=responseBody,state`);
        const replies = new Map((r.included || []).filter((x) => x.type === 'customerReviewResponses').map((x) => [x.id, x.attributes.responseBody]));
        for (const x of r.data || []) {
          const respId = x.relationships && x.relationships.response && x.relationships.response.data && x.relationships.response.data.id;
          reviews.push({
            id: x.id, appId: id, rating: x.attributes.rating, title: x.attributes.title || '', body: x.attributes.body || '',
            author: x.attributes.reviewerNickname || '', territory: x.attributes.territory || '',
            at: Date.parse(x.attributes.createdDate) || 0, reply: respId ? replies.get(respId) || '' : '',
          });
        }
      } catch (_) { /* reviews need the Customer Support role or above */ }
    }));

    let finance;
    let salesDays;
    // 2.0.2: the calendar and history need last refresh's data
    const prevData = (ctx.prev && ctx.prev.appstore && ctx.prev.appstore.data) || {};
    if (!c.vendorNumber) {
      // Sales and Trends need the vendor number in hub/config (appstore.vendorNumber); without it
      // the app list, icons and reviews still flow and the tablet shows no sales numbers
      console.warn('App Store Connect: hub/config appstore.vendorNumber is not set, so sales reports are skipped');
    } else {
      const skus = new Map(raw.map((a) => [a.attributes.sku, a.id]));
      const sales = await salesByApp(token, c, ctx, skus, prevData.salesDays);
      salesDays = sales.salesDays;
      // every app gets its numbers once the reports came back, a zero row included: an app with
      // no sales this week reads 0, not "no data"
      for (const app of apps) {
        const s = sales.byApp.get(app.appleId) || emptySales(sales.home, sales.span);
        metrics[app.id] = { ...(metrics[app.id] || {}), downloads: s.downloads, revenue: s.revenue };
      }
      finance = await financeMonth(token, c, ctx, sales.home, prevData.finance).catch(() => prevData.finance);
    }
    // 2.0.2: the calendar's dated events, and the review-status log carried from refresh to refresh
    const reviewLog = logReviews(prevData.reviewLog, apps, ctx.now);
    const events = [];
    for (const app of apps) events.push(...versionEvents(app.id, versionsOf.get(app.id), listings.get(app.id)));
    events.push(...reviewLogEvents(reviewLog));
    return { apps, metrics, reviews, finance, events, reviewLog, salesDays };
  },
};

/**
 * The daily SALES SUMMARY reports, up to [HISTORY_DAYS] days (Apple keeps a year of daily
 * reports). Each day's totals per app are kept in [cache] (`salesDays`, carried from refresh to
 * refresh), so a refresh asks Apple only for the newest days, which may still change or not be
 * posted yet, and a few older days it doesn't have yet. "today" is the newest day Apple has
 * posted: usually yesterday. Proceeds are converted to the home currency with the ECB rates.
 */
const REFETCH_DAYS = 3;
const BACKFILL_PER_REFRESH = 30;

async function salesByApp(token, c, ctx, skus = new Map(), cache = {}) {
  const home = (c.currency || 'GBP').toUpperCase();
  // Apple's daily reports are Pacific-time days, posted the next morning
  const days = lastDays(HISTORY_DAYS, pacificMidnightUtc(ctx.now) - DAY);
  const old = (cache && cache.home === home && cache.days) || {};
  const want = [];
  for (let i = days.length - 1; i >= 0; i--) {
    const d = days[i];
    const recent = i >= days.length - REFETCH_DAYS;
    if (recent || !(d in old)) want.push(d);
  }
  // the newest days, then the missing older ones, a few at a time
  const fetchNow = want.slice(0, REFETCH_DAYS + BACKFILL_PER_REFRESH);
  const rates = await fxRates(home, ctx.deps.fetch);
  const fresh = {};
  const results = await mapLimit(fetchNow, 6, async (d) => {
    try {
      const q = `filter[frequency]=DAILY&filter[reportType]=SALES&filter[reportSubType]=SUMMARY&filter[vendorNumber]=${encodeURIComponent(c.vendorNumber)}&filter[reportDate]=${d}&filter[version]=1_1`;
      const gz = await request('App Store sales', `${API}/v1/salesReports?${q}`, { headers: { Authorization: `Bearer ${token}`, Accept: 'application/a-gzip' }, raw: true, fetchImpl: ctx.deps.fetch });
      return [d, dayTotals(parseSales(zlib.gunzipSync(gz).toString('utf8')), skus, rates, home)];
    } catch (e) {
      if (e.status === 404) return [d, {}]; // no sales that day
      if (e.status === 410 || (e.status === 400 && /not (yet )?available|no sales|future|too (recent|early)/i.test(e.message))) return [d, null]; // not posted yet
      throw e;
    }
  });
  for (const [d, t] of results) fresh[d] = t;
  // a day not posted yet is left out of the cache, so it's asked for again
  const kept = {};
  for (const d of days) {
    if (d in fresh) { if (fresh[d] !== null) kept[d] = fresh[d]; } else if (d in old) kept[d] = old[d];
  }
  let posted = -1;
  days.forEach((d, i) => { if (kept[d] && (Object.keys(kept[d]).length || i < days.length - REFETCH_DAYS || fresh[d])) posted = i; });
  // the days Apple has (or had) data for: the series starts at the oldest known day
  const first = days.findIndex((d) => d in kept);
  const span = first < 0 ? days.slice(-7) : days.slice(Math.min(first, days.length - 7));
  const byApp = new Map();
  const ids = new Set();
  for (const d of span) for (const id of Object.keys(kept[d] || {})) ids.add(id);
  for (const id of ids) {
    const dl = span.map((d) => ((kept[d] || {})[id] || [0, 0])[0]);
    const rv = span.map((d) => Math.round(((kept[d] || {})[id] || [0, 0])[1] * 100) / 100);
    byApp.set(id, { downloads: seriesValue(dl, span, days, posted), revenue: { ...seriesValue(rv, span, days, posted), currency: home } });
  }
  return { byApp, home, posted, span, salesDays: { home, days: kept } };
}

/** One day's report as { appleId: [downloads, proceeds in home currency] }. */
function dayTotals(rows, skus, rates, home) {
  const out = {};
  for (const r of rows) {
    // in-app purchases name their app by its SKU in Parent Identifier
    const appId = (r.parentId && skus.get(r.parentId)) || r.appleId;
    const t = out[appId] || (out[appId] = [0, 0]);
    if (DOWNLOAD_TYPES.has(r.type)) t[0] += r.units;
    const rate = r.currency === home ? 1 : rates[r.currency];
    if (rate) t[1] += r.proceeds * r.units / rate;
  }
  return out;
}

/** A series over [span] with "today" (the newest posted day) and "week" (the 7 days up to it). */
function seriesValue(series, span, days, posted) {
  const end = posted >= 0 ? span.indexOf(days[posted]) : series.length - 1;
  const e = end < 0 ? series.length - 1 : end;
  const week = series.slice(Math.max(0, e - 6), e + 1).reduce((a, b) => a + b, 0);
  return { series, today: posted >= 0 ? series[e] : 0, week: Math.round(week * 100) / 100, end: span[e] || '' };
}

async function mapLimit(items, n, fn) {
  const out = new Array(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) { const k = i++; out[k] = await fn(items[k]); }
  }));
  return out;
}

function emptySales(home, span) {
  const z = () => Array((span && span.length) || 7).fill(0);
  return { downloads: { series: z(), week: 0, today: 0 }, revenue: { series: z(), week: 0, today: 0, currency: home } };
}

/** The UTC instant of today's midnight in Los Angeles, as a ms timestamp of that calendar date at 00:00 UTC. */
function pacificMidnightUtc(now) {
  const p = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Los_Angeles', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(now));
  return Date.parse(`${p}T00:00:00Z`);
}

function parseSales(tsv) {
  const lines = tsv.replace(/^\uFEFF/, '').split(/\r?\n/).filter((l) => l.trim());
  if (!lines.length) return [];
  const head = lines[0].split('\t').map((h) => h.trim());
  const col = (n) => head.indexOf(n);
  const ix = { id: col('Apple Identifier'), units: col('Units'), proceeds: col('Developer Proceeds'), cur: col('Currency of Proceeds'), type: col('Product Type Identifier'), parent: col('Parent Identifier') };
  return lines.slice(1).map((l) => {
    const f = l.split('\t').map((x) => x.trim());
    return {
      appleId: f[ix.id], units: Number(f[ix.units]) || 0, proceeds: Number(f[ix.proceeds]) || 0,
      currency: (f[ix.cur] || '').toUpperCase(), type: f[ix.type], parentId: ix.parent >= 0 ? f[ix.parent] || '' : '',
    };
  });
}

async function fxRates(home, fetchImpl) {
  try {
    const j = await request('Exchange rates', `https://api.frankfurter.app/latest?from=${home}`, { fetchImpl, timeoutMs: 8000 });
    return j.rates || {};
  } catch (_) { return {}; }
}

/**
 * 2.0.2: the newest fiscal month in Apple's Finance Reports (FINANCIAL, region ZZ, all regions
 * together), as { month: 'YYYY-MM', proceeds, currency, rows }. This is what Apple *reported* you
 * earned in that fiscal month (the sum of Extended Partner Share, converted to the home currency).
 * It isn't a payment: Apple's payment amount and date come from Payments in App Store Connect, after
 * thresholds, tax and bank conversion, and no API gives them, so the hub shows none.
 * Asks for the last three months, newest first; months already read are not asked for again.
 */
async function financeMonth(token, c, ctx, home, prev) {
  const d = new Date(ctx.now);
  const months = [1, 2, 3].map((k) => {
    const m = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - k + 1, 1));
    return `${m.getUTCFullYear()}-${String(m.getUTCMonth() + 1).padStart(2, '0')}`;
  });
  const rates = await fxRates(home, ctx.deps.fetch);
  for (const month of months) {
    if (prev && prev.month === month && prev.currency === home) return prev;
    try {
      const q = `filter[regionCode]=ZZ&filter[reportDate]=${month}&filter[reportType]=FINANCIAL&filter[vendorNumber]=${encodeURIComponent(c.vendorNumber)}`;
      const gz = await request('App Store finance', `${API}/v1/financeReports?${q}`, { headers: { Authorization: `Bearer ${token}`, Accept: 'application/a-gzip' }, raw: true, fetchImpl: ctx.deps.fetch });
      const f = parseFinance(zlib.gunzipSync(gz).toString('utf8'));
      if (!f.rows) continue;
      let total = 0; let missing = false;
      for (const [cur, amt] of Object.entries(f.byCurrency)) {
        const rate = cur === home ? 1 : rates[cur];
        if (!rate) { missing = true; continue; }
        total += amt / rate;
      }
      if (missing && total === 0) return prev && prev.currency === home ? prev : undefined;
      return { month, proceeds: Math.round(total * 100) / 100, currency: home, rows: f.rows, partial: missing };
    } catch (e) {
      if (e.status === 404 || e.status === 410 || e.status === 400) continue; // not posted yet, or nothing that month
      throw e;
    }
  }
  return prev && prev.currency === home ? prev : undefined;
}

/** A Finance Report TSV as { rows, byCurrency: { CUR: sum of Extended Partner Share } }; the total lines at the end are skipped. */
function parseFinance(tsv) {
  const lines = tsv.replace(/^\uFEFF/, '').split(/\r?\n/).filter((l) => l.trim());
  const hi = lines.findIndex((l) => /Extended Partner Share/.test(l));
  if (hi < 0) return { rows: 0, byCurrency: {} };
  const head = lines[hi].split('\t').map((h) => h.trim());
  const ext = head.indexOf('Extended Partner Share');
  const cur = head.indexOf('Partner Share Currency');
  const start = head.indexOf('Start Date');
  const byCurrency = {};
  let rows = 0;
  for (const l of lines.slice(hi + 1)) {
    const f = l.split('\t').map((x) => x.trim());
    if (start >= 0 && !/\d/.test(f[start] || '')) continue; // Total_Rows, Total_Amount, Total_Units ...
    const amt = Number(f[ext]);
    const c = (f[cur] || '').toUpperCase();
    if (!c || Number.isNaN(amt)) continue;
    byCurrency[c] = (byCurrency[c] || 0) + amt;
    rows += 1;
  }
  return { rows, byCurrency };
}

/** Post a reply to a customer review (or replace the one there). Needs the Customer Support role or above. */
async function reply(cfg, { reviewId, text }, deps = {}) {
  const c = cfgOf(cfg);
  const token = ascToken(c);
  const body = {
    data: {
      type: 'customerReviewResponses',
      attributes: { responseBody: String(text).slice(0, 5970) },
      relationships: { review: { data: { type: 'customerReviews', id: String(reviewId) } } },
    },
  };
  return request('App Store Connect', `${API}/v1/customerReviewResponses`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body, fetchImpl: deps.fetch });
}

module.exports = { source, reply, parseSales, parseFinance, financeMonth, salesByApp, pacificMidnightUtc, DOWNLOAD_TYPES, HISTORY_DAYS };
