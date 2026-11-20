'use strict';
/**
 * The aggregator's settings: the plain ones from the Firestore doc hub/config (sites, vendor number,
 * repo list and so on, safe to read in the console), and the keys from Secret Manager, which only
 * the function can read. A secret left as "unset" counts as not set, so a source can stay off.
 */
const SECRET_NAMES = [
  'HUB_TOKEN',
  'ASC_KEY_ID', 'ASC_ISSUER_ID', 'ASC_PRIVATE_KEY',
  'ADMOB_CLIENT_ID', 'ADMOB_CLIENT_SECRET', 'ADMOB_REFRESH_TOKEN',
  'GITHUB_TOKEN', 'BREVO_API_KEY', 'ELEVENLABS_API_KEY',
];

function val(env, k) {
  const v = env[k];
  if (v == null) return '';
  const s = String(v).trim();
  return s === '' || s.toLowerCase() === 'unset' ? '' : s;
}

function buildConfig(doc = {}, env = process.env) {
  const d = doc || {};
  return {
    currency: d.currency || 'GBP',
    appstore: {
      ...(d.appstore || {}),
      keyId: val(env, 'ASC_KEY_ID'), issuerId: val(env, 'ASC_ISSUER_ID'), privateKey: val(env, 'ASC_PRIVATE_KEY'),
      currency: (d.appstore && d.appstore.currency) || d.currency || 'GBP',
    },
    admob: {
      ...(d.admob || {}),
      clientId: val(env, 'ADMOB_CLIENT_ID'), clientSecret: val(env, 'ADMOB_CLIENT_SECRET'), refreshToken: val(env, 'ADMOB_REFRESH_TOKEN'),
    },
    firebase: d.firebase || {},
    crashlytics: d.crashlytics || {},
    sites: d.sites || [],
    brevo: { ...(d.brevo || {}), apiKey: val(env, 'BREVO_API_KEY') },
    github: { ...(d.github || {}), token: val(env, 'GITHUB_TOKEN') },
    billing: d.billing || {},
    elevenlabs: { apiKey: val(env, 'ELEVENLABS_API_KEY') },
    apple: d.apple || {},
    hubToken: val(env, 'HUB_TOKEN'),
  };
}

module.exports = { buildConfig, SECRET_NAMES };
