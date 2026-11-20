'use strict';
/**
 * Brevo transactional email: bounces and blocks per day for 7 days (the Staff Hub's sign-in and
 * shift emails), shown as Email bounces on the site it belongs to (config.brevo.appId).
 * https://developers.brevo.com/reference/getaggregatedsmtpreport-1
 */
const { request } = require('../http');
const { lastDays } = require('../dates');

const source = {
  key: 'brevo',
  label: 'Brevo',
  configured: (cfg) => !!(cfg.brevo && cfg.brevo.apiKey && cfg.brevo.appId),
  async pull(ctx) {
    const c = ctx.cfg.brevo;
    const days = lastDays(7, ctx.now);
    const series = await Promise.all(days.map(async (d) => {
      const j = await request('Brevo', `https://api.brevo.com/v3/smtp/statistics/aggregatedReport?startDate=${d}&endDate=${d}`, {
        headers: { 'api-key': c.apiKey, Accept: 'application/json' }, fetchImpl: ctx.deps.fetch,
      });
      return (Number(j.hardBounces) || 0) + (Number(j.softBounces) || 0) + (Number(j.blocked) || 0);
    }));
    return { metrics: { [c.appId]: { bounces: { today: series[6], week: series.reduce((a, b) => a + b, 0), series } } } };
  },
};

module.exports = { source };
