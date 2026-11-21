'use strict';
/**
 * App Store Connect: the app list (names, bundle ids, icons) for the drawer and status board, the
 * review status of each app's newest version, customer reviews (and replies), and from the Sales
 * and Trends reports: downloads, proceeds and the next payout estimate.
 *
 * Phase 1 needs only the app list. The rest switch on with the same key; sales need the vendor
 * number too (App Store Connect › Payments and Financial Reports, top left).
 */
const zlib = require('zlib');
const { request } = require('../http');
const { ascToken } = require('../ascJwt');
const { DAY, ymd, lastDays } = require('../dates');
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

    let payout;
    if (!c.vendorNumber) {
      // Sales and Trends need the vendor number in hub/config (appstore.vendorNumber); without it
      // the app list, icons and reviews still flow and the tablet shows no sales numbers
      console.warn('App Store Connect: hub/config appstore.vendorNumber is not set, so sales reports are skipped');
    } else {
      const skus = new Map(raw.map((a) => [a.attributes.sku, a.id]));
      const sales = await salesByApp(token, c, ctx, skus);
      // every app gets its numbers once the reports came back, a zero row included: an app with
      // no sales this week reads 0, not "no data"
      for (const app of apps) {
        const s = sales.byApp.get(app.appleId) || emptySales(sales.home, sales.posted);
        metrics[app.id] = { ...(metrics[app.id] || {}), downloads: s.downloads, revenue: s.revenue };
      }
      payout = sales.payout;
    }
    // 2.0.2: the calendar's dated events, and the review-status log carried from refresh to refresh
    const prevData = (ctx.prev && ctx.prev.appstore && ctx.prev.appstore.data) || {};
    const reviewLog = logReviews(prevData.reviewLog, apps, ctx.now);
    const events = [];
    for (const app of apps) events.push(...versionEvents(app.id, versionsOf.get(app.id), listings.get(app.id)));
    events.push(...reviewLogEvents(reviewLog));
    return { apps, metrics, reviews, payout, events, reviewLog };
  },
};

/**
 * The daily SALES SUMMARY reports for the last 7 days (Apple posts each day's the next morning, so
 * "today" is the newest day Apple has: usually yesterday). Proceeds are converted to the home
 * currency with the ECB rates from frankfurter.app.
 */
async function salesByApp(token, c, ctx, skus = new Map()) {
  const home = (c.currency || 'GBP').toUpperCase();
  // Apple's daily reports are Pacific-time days, posted the next morning: take the 7 days up to
  // yesterday in Los Angeles. A day not posted yet comes back empty and the newest posted one is "today".
  const days = lastDays(7, pacificMidnightUtc(ctx.now) - DAY);
  const byApp = new Map();
  const rates = await fxRates(home, ctx.deps.fetch);
  const reports = await Promise.all(days.map(async (d) => {
    try {
      const q = `filter[frequency]=DAILY&filter[reportType]=SALES&filter[reportSubType]=SUMMARY&filter[vendorNumber]=${encodeURIComponent(c.vendorNumber)}&filter[reportDate]=${d}&filter[version]=1_1`;
      const gz = await request('App Store sales', `${API}/v1/salesReports?${q}`, { headers: { Authorization: `Bearer ${token}`, Accept: 'application/a-gzip' }, raw: true, fetchImpl: ctx.deps.fetch });
      return parseSales(zlib.gunzipSync(gz).toString('utf8'));
    } catch (e) {
      if (e.status === 404) return []; // no sales that day
      if (e.status === 410 || (e.status === 400 && /not (yet )?available|no sales|future|too (recent|early)/i.test(e.message))) return []; // not posted yet
      throw e;
    }
  }));
  let posted = -1;
  reports.forEach((rows, i) => { if (rows.length) posted = i; });
  reports.forEach((rows, i) => {
    for (const r of rows) {
      // in-app purchases name their app by its SKU in Parent Identifier
      const appId = (r.parentId && skus.get(r.parentId)) || r.appleId;
      const s = byApp.get(appId) || { downloads: { series: Array(7).fill(0) }, revenue: { series: Array(7).fill(0), currency: home } };
      if (DOWNLOAD_TYPES.has(r.type)) s.downloads.series[i] += r.units;
      const rate = r.currency === home ? 1 : rates[r.currency];
      if (rate) s.revenue.series[i] += r.proceeds * r.units / rate;
      byApp.set(appId, s);
    }
  });
  for (const s of byApp.values()) {
    for (const k of ['downloads', 'revenue']) {
      s[k].week = s[k].series.reduce((a, b) => a + b, 0);
      s[k].today = posted >= 0 ? s[k].series[posted] : 0;
    }
  }
  return { byApp, home, posted, payout: payoutEstimate(byApp, home, ctx.now, c) };
}

function emptySales(home, posted) {
  const z = () => Array(7).fill(0);
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
 * The next payout, roughly: Apple pays a fiscal month's proceeds about 33 days after it ends, once
 * they pass the threshold. This takes calendar months and the last 7 days' run rate, so it's an
 * estimate (the tablet labels it so). A date set in config.appstore.nextPayment wins.
 */
function payoutEstimate(byApp, home, now, c) {
  let week = 0;
  for (const s of byApp.values()) week += s.revenue.week || 0;
  const d = new Date(now);
  const prevMonthEnd = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 0);
  let next = prevMonthEnd + 33 * DAY;
  if (next < now) next = Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0) + 33 * DAY;
  const daysInMonth = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
  return {
    nextDate: c.nextPayment || ymd(next),
    estimate: Math.round((week / 7) * daysInMonth * 100) / 100,
    currency: home,
  };
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

module.exports = { source, reply, parseSales, payoutEstimate, pacificMidnightUtc, DOWNLOAD_TYPES };
