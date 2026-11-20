'use strict';
/**
 * VoidOS Void Hub aggregator (Firebase Cloud Functions, 2nd gen).
 *
 *   hub        HTTPS: GET /summary (?wake=1 refreshes first, at most every 10 minutes), POST /reply
 *   hubHourly  every hour: refresh every source and save the summary
 *
 * Everything it keeps is in Firestore: hub/summary (what the tablet reads), hub/state (each
 * source's last good data and error) and hub/config (your plain settings). Keys live in Secret
 * Manager only. Setup: docs/hub-setup.md.
 */
const { onRequest } = require('firebase-functions/v2/https');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { defineSecret } = require('firebase-functions/params');
const admin = require('firebase-admin');
const { buildConfig, SECRET_NAMES } = require('./lib/config');
const agg = require('./lib/aggregate');
const server = require('./lib/server');
const sources = require('./lib/sources');

admin.initializeApp();
const db = admin.firestore();
const secrets = SECRET_NAMES.map((n) => defineSecret(n));
const REGION = 'europe-west2';

// summaries and state are kept as JSON text: Firestore can't hold every shape the sources return
const store = {
  async getSummary() { const d = await db.doc('hub/summary').get(); return d.exists ? JSON.parse(d.get('json')) : null; },
  async setSummary(s) { await db.doc('hub/summary').set({ json: JSON.stringify(s), updatedAt: s.updatedAt }); },
  async getState() { const d = await db.doc('hub/state').get(); return d.exists ? JSON.parse(d.get('json')) : null; },
  async setState(s) { await db.doc('hub/state').set({ json: JSON.stringify(s) }); },
};

async function config() {
  const d = await db.doc('hub/config').get();
  return buildConfig(d.exists ? d.data() : {}, process.env);
}

exports.hub = onRequest({ region: REGION, secrets, timeoutSeconds: 120, memory: '256MiB', maxInstances: 2 }, async (req, res) => {
  const r = await server.handle(
    { method: req.method, path: req.path, query: req.query, headers: req.headers, body: req.body },
    { store, sources, cfg: await config(), now: Date.now(), deps: {} },
  );
  res.status(r.status).set('Cache-Control', 'no-store').json(r.body);
});

exports.hubHourly = onSchedule({ schedule: 'every 60 minutes', region: REGION, secrets, timeoutSeconds: 300, memory: '256MiB' }, async () => {
  await agg.refresh({ store, sources, cfg: await config(), now: Date.now(), deps: {}, force: true });
});
