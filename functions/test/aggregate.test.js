'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('crypto');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');
const agg = require('../lib/aggregate');
const { ascToken } = require('../lib/ascJwt');
const appstore = require('../lib/sources/appstore');
const admob = require('../lib/sources/admob');
const sites = require('../lib/sources/sites');
const firebase = require('../lib/sources/firebase');
const { buildConfig } = require('../lib/config');
const server = require('../lib/server');
const allSources = require('../lib/sources');

const NOW = Date.UTC(2026, 10, 20, 14, 0, 0); // 20 Nov 2026, 2 PM
const MIN = 60000;

function memStore(summary = null, state = null) {
  return {
    summary, state, writes: 0,
    async getSummary() { return this.summary && JSON.parse(JSON.stringify(this.summary)); },
    async setSummary(s) { this.summary = s; this.writes++; },
    async getState() { return this.state; },
    async setState(s) { this.state = s; },
  };
}

/** A fake fetch that answers by URL pattern. */
function fakeFetch(routes) {
  const calls = [];
  const f = async (url, init = {}) => {
    calls.push({ url, init });
    for (const [re, h] of routes) {
      if (re.test(url)) {
        const r = await h(url, init);
        const status = r.status || 200;
        const buf = r.raw ? r.raw : Buffer.from(r.text != null ? r.text : JSON.stringify(r.json));
        return {
          ok: status < 300, status,
          text: async () => buf.toString('utf8'),
          arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.length),
        };
      }
    }
    return { ok: false, status: 404, text: async () => '', arrayBuffer: async () => new ArrayBuffer(0) };
  };
  f.calls = calls;
  return f;
}

const { privateKey: P8, publicKey: PUB } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
const P8_PEM = P8.export({ type: 'pkcs8', format: 'pem' });

test('refreshes at most every ten minutes', () => {
  assert.ok(agg.shouldRefresh(0, NOW));
  assert.ok(!agg.shouldRefresh(NOW - 9 * MIN, NOW));
  assert.ok(agg.shouldRefresh(NOW - 10 * MIN, NOW));
  assert.ok(agg.shouldRefresh(NOW + MIN, NOW));
});

test('App Store Connect token is a valid ES256 JWT for 19 minutes', () => {
  const t = ascToken({ keyId: 'ABC123', issuerId: 'iss-1', privateKey: P8_PEM }, NOW);
  const [h, p, s] = t.split('.');
  const header = JSON.parse(Buffer.from(h, 'base64url'));
  const payload = JSON.parse(Buffer.from(p, 'base64url'));
  assert.deepStrictEqual(header, { alg: 'ES256', kid: 'ABC123', typ: 'JWT' });
  assert.strictEqual(payload.aud, 'appstoreconnect-v1');
  assert.strictEqual(payload.exp - payload.iat, 19 * 60);
  assert.ok(crypto.verify('sha256', Buffer.from(`${h}.${p}`), { key: PUB, dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url')));
  // a key pasted on one line, with its newlines lost, still signs
  const oneLine = P8_PEM.replace(/\n/g, ' ');
  assert.ok(ascToken({ keyId: 'A', issuerId: 'B', privateKey: oneLine }, NOW).split('.').length === 3);
});

test('a failing source keeps its last good data and says why', async () => {
  let fail = false;
  const src = { key: 'x', label: 'X', first: true, configured: () => true, async pull() { if (fail) throw new Error('X: HTTP 500 Bearer abc.def'); return { apps: [{ id: 'a', name: 'A', kind: 'app' }], metrics: { a: { downloads: { today: 3 } } } }; } };
  const off = { key: 'y', label: 'Y', configured: () => false, async pull() { throw new Error('never'); } };
  const s1 = await agg.runSources([src, off], {}, {}, NOW);
  fail = true;
  const s2 = await agg.runSources([src, off], s1, {}, NOW + 3600000);
  assert.strictEqual(s2.sources.x.ok, false);
  assert.strictEqual(s2.sources.x.at, NOW);
  assert.match(s2.sources.x.error, /HTTP 500/);
  assert.doesNotMatch(s2.sources.x.error, /abc\.def/);
  assert.strictEqual(s2.sources.y.connected, false);
  const sum = agg.buildSummary(s2, {}, NOW + 3600000);
  assert.strictEqual(sum.apps[0].metrics.downloads.today, 3);
  assert.strictEqual(sum.sources.x.ok, false);
  assert.ok(!('data' in sum.sources.x));
});

function salesTsv(rows) {
  const head = ['Provider', 'Provider Country', 'SKU', 'Developer', 'Title', 'Version', 'Product Type Identifier', 'Units', 'Developer Proceeds', 'Begin Date', 'End Date', 'Customer Currency', 'Country Code', 'Currency of Proceeds', 'Apple Identifier', 'Customer Price', 'Promo Code', 'Parent Identifier'];
  const lines = rows.map((r) => head.map((h) => (r[h] != null ? r[h] : '')).join('\t'));
  return zlib.gzipSync([head.join('\t'), ...lines].join('\n'));
}

function ascRoutes() {
  return [
    [/\/v1\/apps\?/, () => ({ json: { data: [
      { id: '111', attributes: { name: 'Voided', bundleId: 'com.andysocial.voided', sku: 'VOIDED' } },
      { id: '222', attributes: { name: 'Button Panic', bundleId: 'com.andysocial.buttonpanic', sku: 'BP' } },
    ] } })],
    [/itunes\.apple\.com\/lookup\?id=111/, () => ({ json: { results: [{ artworkUrl512: 'https://is1.mzstatic.com/voided.png', averageUserRating: 4.6 }] } })],
    [/itunes\.apple\.com\/lookup/, () => ({ json: { results: [] } })],
    [/apps\/111\/appStoreVersions/, () => ({ json: { data: [{ attributes: { versionString: '1.4', appVersionState: 'WAITING_FOR_REVIEW', createdDate: '2026-11-18T10:00:00Z' } }] } })],
    [/apps\/222\/appStoreVersions/, () => ({ json: { data: [{ attributes: { versionString: '2.0', appStoreState: 'READY_FOR_SALE', createdDate: '2026-11-01T10:00:00Z' } }] } })],
    [/apps\/111\/customerReviews/, () => ({ json: {
      data: [{ id: 'r1', attributes: { rating: 5, title: 'Great', body: 'Love the rift', reviewerNickname: 'kev', territory: 'GBR', createdDate: '2026-11-20T09:00:00Z' }, relationships: { response: { data: { id: 'p1' } } } },
        { id: 'r2', attributes: { rating: 2, title: 'Hard', body: 'Round 20!', reviewerNickname: 'sam', territory: 'USA', createdDate: '2026-11-19T09:00:00Z' }, relationships: { response: { data: null } } }],
      included: [{ type: 'customerReviewResponses', id: 'p1', attributes: { responseBody: 'Thanks Kev!' } }],
    } })],
    [/apps\/222\/customerReviews/, () => ({ json: { data: [] } })],
    [/salesReports.*reportDate\]=2026-11-19/, () => ({ raw: salesTsv([
      { 'Apple Identifier': '111', Units: '7', 'Developer Proceeds': '0', 'Product Type Identifier': '1F', 'Currency of Proceeds': 'GBP' },
      { 'Apple Identifier': '111', Units: '3', 'Developer Proceeds': '0', 'Product Type Identifier': '7F', 'Currency of Proceeds': 'GBP' },
      { 'Apple Identifier': '999', Units: '2', 'Developer Proceeds': '1.50', 'Product Type Identifier': 'IA1', 'Currency of Proceeds': 'USD', 'Parent Identifier': 'VOIDED' },
      { 'Apple Identifier': '222', Units: '1', 'Developer Proceeds': '0.70', 'Product Type Identifier': '1F', 'Currency of Proceeds': 'GBP' },
    ]) })],
    [/salesReports.*reportDate\]=2026-11-18/, () => ({ raw: salesTsv([{ 'Apple Identifier': '111', Units: '4', 'Developer Proceeds': '0', 'Product Type Identifier': '1F', 'Currency of Proceeds': 'GBP' }]) })],
    [/salesReports/, () => ({ status: 404, json: { errors: [{ detail: 'no report' }] } })],
    [/frankfurter/, () => ({ json: { base: 'GBP', rates: { USD: 1.25 } } })],
  ];
}

test('App Store Connect: apps, icons, review state, reviews and sales', async () => {
  const f = fakeFetch(ascRoutes());
  const cfg = buildConfig({ appstore: { vendorNumber: '85000000' } }, { ASC_KEY_ID: 'K', ASC_ISSUER_ID: 'I', ASC_PRIVATE_KEY: P8_PEM });
  assert.ok(appstore.source.configured(cfg));
  const d = await appstore.source.pull({ cfg, now: NOW, deps: { fetch: f }, apps: [] });
  const v = d.apps.find((a) => a.id === 'asc:111');
  assert.strictEqual(v.name, 'Voided');
  assert.strictEqual(v.icon, 'https://is1.mzstatic.com/voided.png');
  assert.deepStrictEqual(v.review, { state: 'WAITING_FOR_REVIEW', version: '1.4', since: Date.parse('2026-11-18T10:00:00Z') });
  assert.strictEqual(d.apps.find((a) => a.id === 'asc:222').icon, undefined);
  // only first-time downloads count; the update (7F) doesn't
  assert.ok(d.metrics['asc:111'].downloads, JSON.stringify(d.metrics) + JSON.stringify(f.calls.filter((c) => /sales/.test(c.url)).map((c) => c.url.slice(-60))));
  assert.strictEqual(d.metrics['asc:111'].downloads.today, 7);
  assert.strictEqual(d.metrics['asc:111'].downloads.week, 11);
  // the in-app purchase rolls up to Voided by its parent SKU: 2 × $1.50 at 1.25 = £2.40
  assert.strictEqual(Math.round(d.metrics['asc:111'].revenue.today * 100) / 100, 2.4);
  assert.strictEqual(d.metrics['asc:111'].rating.today, 4.6);
  assert.strictEqual(d.reviews.find((r) => r.id === 'r1').reply, 'Thanks Kev!');
  assert.strictEqual(d.reviews.find((r) => r.id === 'r2').reply, '');
  assert.ok(d.payout && d.payout.nextDate && d.payout.currency === 'GBP');
  // every App Store Connect call carried the token; none went anywhere else with it
  for (const c of f.calls) {
    const auth = c.init.headers && c.init.headers.Authorization;
    if (/api\.appstoreconnect/.test(c.url)) assert.match(auth, /^Bearer ey/);
    else assert.ok(!auth, `token sent to ${c.url}`);
  }
});

test('reply posts a customerReviewResponse', async () => {
  let sent;
  const f = fakeFetch([[/customerReviewResponses/, (u, init) => { sent = JSON.parse(init.body); return { status: 201, json: { data: { id: 'p9' } } }; }]]);
  const cfg = buildConfig({}, { ASC_KEY_ID: 'K', ASC_ISSUER_ID: 'I', ASC_PRIVATE_KEY: P8_PEM });
  await appstore.reply(cfg, { reviewId: 'r2', text: 'Calm mode is coming!' }, { fetch: f });
  assert.strictEqual(sent.data.type, 'customerReviewResponses');
  assert.strictEqual(sent.data.attributes.responseBody, 'Calm mode is coming!');
  assert.deepStrictEqual(sent.data.relationships.review.data, { type: 'customerReviews', id: 'r2' });
});

test('AdMob report rows become per-app earnings, impressions and eCPM', async () => {
  const days = ['2026-11-14', '2026-11-15', '2026-11-16', '2026-11-17', '2026-11-18', '2026-11-19', '2026-11-20'];
  const rows = [{ header: {} },
    { row: { dimensionValues: { DATE: { value: '20261120' }, APP: { value: 'ca-app-pub-1~9' } }, metricValues: { ESTIMATED_EARNINGS: { microsValue: '1500000' }, IMPRESSIONS: { integerValue: '500' } } } },
    { row: { dimensionValues: { DATE: { value: '20261119' }, APP: { value: 'ca-app-pub-1~9' } }, metricValues: { ESTIMATED_EARNINGS: { microsValue: '500000' }, IMPRESSIONS: { integerValue: '250' } } } },
    { footer: {} }];
  const f = fakeFetch([
    [/oauth2/, () => ({ json: { access_token: 'g' } })],
    [/\/apps\?/, () => ({ json: { apps: [{ appId: 'ca-app-pub-1~9', linkedAppInfo: { appStoreId: '111' } }] } })],
    [/networkReport:generate/, () => ({ json: rows })],
  ]);
  const cfg = buildConfig({ admob: { publisherId: 'pub-1' } }, { ADMOB_CLIENT_ID: 'c', ADMOB_CLIENT_SECRET: 's', ADMOB_REFRESH_TOKEN: 'r' });
  const d = await admob.source.pull({ cfg, now: NOW, deps: { fetch: f }, apps: [{ id: 'asc:111', appleId: '111' }] });
  assert.strictEqual(d.metrics['asc:111'].ads.today, 1.5);
  assert.strictEqual(d.metrics['asc:111'].impressions.week, 750);
  assert.strictEqual(d.metrics['asc:111'].ecpm.today, 3);
  assert.strictEqual(Math.round(d.metrics['asc:111'].ecpm.week * 100) / 100, 2.67);
  assert.deepStrictEqual(admob.parseReport(rows, days, {}), {});
});

test('Firebase Analytics: daily active users and the week\'s unique users', async () => {
  const f = fakeFetch([[/runReport/, (u, init) => {
    const b = JSON.parse(init.body);
    if (b.dimensions) return { json: { rows: [{ dimensionValues: [{ value: '20261120' }], metricValues: [{ value: '42' }] }, { dimensionValues: [{ value: '20261118' }], metricValues: [{ value: '30' }] }] } };
    return { json: { rows: [{ metricValues: [{ value: '61' }] }] } };
  }]]);
  const cfg = buildConfig({ firebase: { properties: [{ propertyId: '123', bundleId: 'com.andysocial.voided' }] } }, {});
  const d = await firebase.source.pull({ cfg, now: NOW, deps: { fetch: f, googleToken: async () => 't' }, apps: [{ id: 'asc:111', bundleId: 'com.andysocial.voided' }] });
  assert.deepStrictEqual(d.metrics['asc:111'].users, { today: 42, week: 61, series: [0, 0, 0, 0, 30, 0, 42] });
});

test('sites: uptime from the kept checks, SSL and domain days', async () => {
  assert.strictEqual(sites.registrable('www.tws-rota.co.uk'), 'tws-rota.co.uk');
  assert.strictEqual(sites.registrable('hub.example.com'), 'example.com');
  const f = fakeFetch([[/tws-rota/, () => ({ status: 200, text: 'ok' })], [/whiteswan/, () => ({ status: 503, text: 'down' })]]);
  const cfg = buildConfig({ sites: [{ name: 'Staff Hub', url: 'https://tws-rota.co.uk' }, { name: 'White Swan', url: 'https://whiteswan.example/' }] }, {});
  const prev = { sites: { data: { history: { 'site:staff-hub': [{ at: NOW - 3600000, up: false }, { at: NOW - 7200000, up: true }, { at: NOW - 9 * 86400000, up: false }] } } } };
  const deps = { fetch: f, sslDaysLeft: async () => 9, domainDaysLeft: async () => 200 };
  const d = await sites.source.pull({ cfg, now: NOW, deps, apps: [], prev });
  const hub = d.sites.find((s) => s.appId === 'site:staff-hub');
  assert.deepStrictEqual([hub.up, hub.sslDays, hub.domainDays], [true, 9, 200]);
  // 2 of today's 3 checks were up; the 9-day-old check was dropped
  assert.strictEqual(Math.round(d.metrics['site:staff-hub'].uptime.today), 67);
  assert.strictEqual(d.history['site:staff-hub'].length, 3);
  assert.strictEqual(d.sites.find((s) => s.appId === 'site:white-swan').up, false);
  // and the summary glows: errors for the site that's down, attention for the certificate
  const st = { sources: { sites: { label: 'Uptime checks', connected: true, ok: true, at: NOW, data: d } } };
  const sum = agg.buildSummary(st, cfg, NOW);
  const ws = sum.apps.find((a) => a.id === 'site:white-swan');
  assert.strictEqual(ws.status, 'errors');
  assert.match(ws.statusNote, /whiteswan\.example is down/);
  const sh = sum.apps.find((a) => a.id === 'site:staff-hub');
  assert.strictEqual(sh.status, 'attention');
  assert.match(sh.statusNote, /SSL expires in 9 days/);
});

test('secrets left as "unset" keep a source off', () => {
  const cfg = buildConfig({}, { ASC_KEY_ID: 'unset', ASC_ISSUER_ID: 'I', ASC_PRIVATE_KEY: 'x', GITHUB_TOKEN: ' unset ' });
  for (const s of allSources) assert.strictEqual(s.configured(cfg), false, s.key);
});

test('server: token, wake refresh at most every 10 minutes, reply', async () => {
  const f = fakeFetch(ascRoutes());
  const cfg = buildConfig({ appstore: { vendorNumber: '85000000' }, sites: [] }, { HUB_TOKEN: 'tablet-secret', ASC_KEY_ID: 'K', ASC_ISSUER_ID: 'I', ASC_PRIVATE_KEY: P8_PEM });
  const store = memStore();
  const env = (now, extra = {}) => ({ store, sources: allSources, cfg, now, deps: { fetch: f, ...extra } });
  const get = (q, tok = 'tablet-secret') => ({ method: 'GET', path: '/summary', query: q, headers: { authorization: `Bearer ${tok}` } });

  assert.strictEqual((await server.handle(get({}, 'wrong'), env(NOW))).status, 401);
  assert.strictEqual((await server.handle(get({}, ''), env(NOW))).status, 401);
  // the first call fills the summary
  const r1 = await server.handle(get({}), env(NOW));
  assert.strictEqual(r1.status, 200);
  assert.strictEqual(r1.body.apps.length, 2);
  assert.strictEqual(store.writes, 1);
  // a wake 5 minutes later doesn't refresh; 11 minutes later it does
  await server.handle(get({ wake: '1' }), env(NOW + 5 * MIN));
  assert.strictEqual(store.writes, 1);
  await server.handle(get({ wake: '1' }), env(NOW + 11 * MIN));
  assert.strictEqual(store.writes, 2);
  // reply
  let replied;
  const rep = await server.handle({ method: 'POST', path: '/reply', headers: { authorization: 'Bearer tablet-secret' }, body: { reviewId: 'r2', appId: 'asc:111', text: 'Thanks Sam' } },
    env(NOW, { reply: async (c, a) => { replied = a; } }));
  assert.strictEqual(rep.status, 200);
  assert.deepStrictEqual(replied, { reviewId: 'r2', text: 'Thanks Sam' });
  assert.strictEqual(store.summary.reviews.find((r) => r.id === 'r2').reply, 'Thanks Sam');
  assert.strictEqual((await server.handle({ method: 'POST', path: '/reply', headers: { authorization: 'Bearer tablet-secret' }, body: { reviewId: 'r2' } }, env(NOW))).status, 400);
  // no token configured on the server: nothing is served
  const bare = { ...env(NOW), cfg: buildConfig({}, {}) };
  assert.strictEqual((await server.handle(get({}), bare)).status, 503);

  // the summary the tablet will parse: kept as a shared fixture for the app's contract test
  const sum = store.summary;
  const v = sum.apps.find((a) => a.id === 'asc:111');
  assert.strictEqual(v.status, 'attention');
  assert.strictEqual(v.latestReview.id, 'r1');
  assert.strictEqual(sum.sources.appstore.ok, true);
  assert.strictEqual(sum.sources.admob.connected, false);
  const out = path.join(__dirname, 'fixtures', 'summary-sample.json');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, `${JSON.stringify(sum, null, 2)}\n`);
});
