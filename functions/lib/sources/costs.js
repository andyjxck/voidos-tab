'use strict';
/**
 * Running costs: Google Cloud and Firebase spend this month (from the billing export to BigQuery),
 * the ElevenLabs credit balance, and the Apple Developer Program renewal (a date you enter).
 */
const { request } = require('../http');
const { query } = require('../bigquery');
const { daysUntil } = require('../dates');

const billing = {
  key: 'billing',
  label: 'Google Cloud billing',
  configured: (cfg) => !!(cfg.billing && cfg.billing.table),
  async pull(ctx) {
    const c = ctx.cfg.billing;
    const project = c.project || c.table.split('.')[0];
    const rows = await query(project, `SELECT currency, SUM(cost) + SUM(IFNULL((SELECT SUM(x.amount) FROM UNNEST(credits) x), 0)) AS total
      FROM \`${c.table}\` WHERE invoice.month = FORMAT_DATE('%Y%m', CURRENT_DATE()) GROUP BY currency`, ctx.deps);
    const r = rows[0] || { currency: c.currency || 'GBP', total: 0 };
    const amount = Math.round(Number(r.total) * 100) / 100;
    return { costs: [{ key: 'gcp', label: 'Google Cloud & Firebase', amount, currency: r.currency, note: 'this month so far', warn: c.budget != null && amount >= Number(c.budget) }] };
  },
};

const elevenlabs = {
  key: 'elevenlabs',
  label: 'ElevenLabs',
  configured: (cfg) => !!(cfg.elevenlabs && cfg.elevenlabs.apiKey),
  async pull(ctx) {
    const j = await request('ElevenLabs', 'https://api.elevenlabs.io/v1/user/subscription', { headers: { 'xi-api-key': ctx.cfg.elevenlabs.apiKey }, fetchImpl: ctx.deps.fetch });
    const left = Math.max(0, (Number(j.character_limit) || 0) - (Number(j.character_count) || 0));
    const reset = j.next_character_count_reset_unix ? new Date(j.next_character_count_reset_unix * 1000).toISOString().slice(0, 10) : '';
    return { costs: [{ key: 'elevenlabs', label: 'ElevenLabs', amount: left, currency: 'credits', note: 'credits left', due: reset, warn: j.character_limit ? left < j.character_limit * 0.1 : false }] };
  },
};

const apple = {
  key: 'apple',
  label: 'Apple Developer renewal',
  configured: (cfg) => !!(cfg.apple && cfg.apple.renewal),
  async pull(ctx) {
    const c = ctx.cfg.apple;
    const days = daysUntil(c.renewal, ctx.now);
    return { costs: [{ key: 'apple', label: 'Apple Developer Program', amount: c.fee != null ? Number(c.fee) : null, currency: c.currency || 'GBP', note: 'renews', due: c.renewal, warn: days != null && days <= 30 }] };
  },
};

module.exports = { billing, elevenlabs, apple };
