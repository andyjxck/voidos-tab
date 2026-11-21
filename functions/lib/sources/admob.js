'use strict';
/**
 * AdMob: estimated earnings, impressions and eCPM per app for the last 7 days, from the AdMob API's
 * network report. AdMob only takes OAuth user credentials, so this uses a refresh token you make
 * once (docs/hub-setup.md). Apps are matched to the App Store list by their linked store id.
 * https://developers.google.com/admob/api/v1/reference/rest/v1/accounts.networkReport/generate
 */
const { request, googleRefreshToken } = require('../http');
const { DAY, lastDays, HISTORY_DAYS } = require('../dates');

const API = 'https://admob.googleapis.com/v1';
const cfgOf = (cfg) => cfg.admob || {};

function ymdParts(s) { const [y, m, d] = s.split('-').map(Number); return { year: y, month: m, day: d }; }

/** Turn the streamed report rows into per-app daily series over [days]. */
function parseReport(rows, days, appMap) {
  const out = {};
  for (const item of rows || []) {
    const r = item.row;
    if (!r) continue;
    const date = r.dimensionValues.DATE.value; // YYYYMMDD
    const i = days.indexOf(`${date.slice(0, 4)}-${date.slice(4, 6)}-${date.slice(6, 8)}`);
    if (i < 0) continue;
    const admobId = r.dimensionValues.APP.value;
    const id = appMap[admobId];
    if (!id) continue;
    const o = out[id] || (out[id] = { ads: Array(days.length).fill(0), impressions: Array(days.length).fill(0) });
    o.ads[i] += Number((r.metricValues.ESTIMATED_EARNINGS || {}).microsValue || 0) / 1e6;
    o.impressions[i] += Number((r.metricValues.IMPRESSIONS || {}).integerValue || 0);
  }
  return out;
}

const source = {
  key: 'admob',
  label: 'AdMob',
  configured: (cfg) => { const c = cfgOf(cfg); return !!(c.publisherId && c.clientId && c.clientSecret && c.refreshToken); },
  async pull(ctx) {
    const c = cfgOf(ctx.cfg); const f = ctx.deps.fetch;
    const cur = (c.currency || ctx.cfg.currency || 'GBP').toUpperCase();
    const token = await googleRefreshToken(c, f);
    const auth = { Authorization: `Bearer ${token}` };
    const acct = `accounts/${c.publisherId.replace(/^accounts\//, '')}`;
    const list = await request('AdMob', `${API}/${acct}/apps?pageSize=100`, { headers: auth, fetchImpl: f });
    const appMap = {};
    for (const a of list.apps || []) {
      const store = a.linkedAppInfo && a.linkedAppInfo.appStoreId;
      const asc = ctx.apps.find((x) => x.appleId === store || x.bundleId === store);
      if (asc) appMap[a.appId] = asc.id;
      else if (c.map && c.map[a.appId]) appMap[a.appId] = c.map[a.appId];
    }
    // 2.0.2: a year of days in the one report, so the tablet can show any range
    const days = lastDays(HISTORY_DAYS, ctx.now);
    const body = {
      reportSpec: {
        dateRange: { startDate: ymdParts(days[0]), endDate: ymdParts(days[days.length - 1]) },
        dimensions: ['DATE', 'APP'],
        metrics: ['ESTIMATED_EARNINGS', 'IMPRESSIONS'],
        localizationSettings: { currencyCode: cur },
      },
    };
    const rows = await request('AdMob', `${API}/${acct}/networkReport:generate`, { method: 'POST', headers: auth, body, fetchImpl: f });
    const per = parseReport(rows, days, appMap);
    const metrics = {};
    for (const [id, o] of Object.entries(per)) {
      const sum = (xs) => xs.reduce((a, b) => a + b, 0);
      const last7 = (xs) => xs.slice(-7);
      const n = o.ads.length - 1;
      const ecpm = o.ads.map((e, i) => (o.impressions[i] ? (e / o.impressions[i]) * 1000 : 0));
      const wk = sum(last7(o.impressions)) ? (sum(last7(o.ads)) / sum(last7(o.impressions))) * 1000 : 0;
      metrics[id] = {
        ads: { today: o.ads[n], week: sum(last7(o.ads)), series: o.ads, currency: cur },
        impressions: { today: o.impressions[n], week: sum(last7(o.impressions)), series: o.impressions },
        ecpm: { today: ecpm[n], week: wk, series: ecpm, currency: cur },
      };
    }
    return { metrics };
  },
};

module.exports = { source, parseReport, DAY };
