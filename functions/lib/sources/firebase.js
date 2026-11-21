'use strict';
/**
 * Firebase Analytics active users per app, from the Google Analytics Data API. Each Firebase
 * project links to a GA4 property; list them in config.firebase.properties with the app's bundle
 * id (or the hub app id, like site:staff-hub). The function's service account needs Viewer on each
 * property. https://developers.google.com/analytics/devguides/reporting/data/v1/rest/v1beta/properties/runReport
 */
const { request, googleToken } = require('../http');
const { lastDays, HISTORY_DAYS } = require('../dates');

const SCOPES = 'https://www.googleapis.com/auth/analytics.readonly';

function parseRows(j, days) {
  const series = Array(days.length).fill(0);
  for (const r of (j && j.rows) || []) {
    const v = r.dimensionValues[0].value;
    const i = days.indexOf(`${v.slice(0, 4)}-${v.slice(4, 6)}-${v.slice(6, 8)}`);
    if (i >= 0) series[i] = Number(r.metricValues[0].value) || 0;
  }
  return series;
}

const source = {
  key: 'firebase',
  label: 'Firebase Analytics',
  configured: (cfg) => !!(cfg.firebase && Array.isArray(cfg.firebase.properties) && cfg.firebase.properties.length),
  async pull(ctx) {
    const f = ctx.deps.fetch;
    const token = ctx.deps.googleToken ? await ctx.deps.googleToken(SCOPES) : await googleTokenScoped(f);
    const days = lastDays(HISTORY_DAYS, ctx.now);
    const metrics = {};
    const errors = [];
    await Promise.all(ctx.cfg.firebase.properties.map(async (p) => {
      const id = p.appId || (ctx.apps.find((a) => a.bundleId === p.bundleId) || {}).id;
      if (!id) return;
      try {
        const base = { dateRanges: [{ startDate: '6daysAgo', endDate: 'today' }], metrics: [{ name: 'activeUsers' }] };
        const url = `https://analyticsdata.googleapis.com/v1beta/properties/${p.propertyId}:runReport`;
        const auth = { Authorization: `Bearer ${token}` };
        // the week's figure is unique users over the 7 days, not the daily sum
        const [daily, week] = await Promise.all([
          request('Google Analytics', url, { method: 'POST', headers: auth, body: { ...base, dateRanges: [{ startDate: `${HISTORY_DAYS - 1}daysAgo`, endDate: 'today' }], dimensions: [{ name: 'date' }], limit: 1000 }, fetchImpl: f }),
          request('Google Analytics', url, { method: 'POST', headers: auth, body: base, fetchImpl: f }),
        ]);
        const series = parseRows(daily, days);
        const wk = week && week.rows && week.rows[0] ? Number(week.rows[0].metricValues[0].value) : null;
        metrics[id] = { users: { today: series[series.length - 1], week: wk, series } };
      } catch (e) { errors.push(e.message); }
    }));
    if (errors.length && !Object.keys(metrics).length) throw new Error(errors[0]);
    return { metrics };
  },
};

async function googleTokenScoped(fetchImpl) {
  const j = await request('Google metadata', `http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token?scopes=${encodeURIComponent(SCOPES + ',https://www.googleapis.com/auth/cloud-platform')}`, {
    headers: { 'Metadata-Flavor': 'Google' }, timeoutMs: 5000, fetchImpl,
  });
  return j.access_token;
}

module.exports = { source, parseRows, googleToken };
