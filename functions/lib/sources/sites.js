'use strict';
/**
 * Your websites (manual entries in the config): an uptime check each refresh, the SSL certificate's
 * days left, and the domain's renewal date from RDAP. Uptime % is worked out from the checks of
 * the last day and week, which this source keeps in its own data between refreshes.
 */
const tls = require('tls');
const { request } = require('../http');
const { DAY, ymd, lastDays, daysUntil } = require('../dates');

const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const hostOf = (url) => new URL(url).hostname;

/** example.co.uk from www.example.co.uk; example.com from shop.example.com. */
function registrable(host) {
  const p = host.split('.');
  const second = ['co', 'org', 'ac', 'gov', 'me', 'ltd', 'plc', 'net', 'com'];
  if (p.length >= 3 && p[p.length - 1].length === 2 && second.includes(p[p.length - 2])) return p.slice(-3).join('.');
  return p.slice(-2).join('.');
}

function sslDaysLeft(host, now, timeoutMs = 8000) {
  return new Promise((resolve) => {
    const sock = tls.connect({ host, port: 443, servername: host, timeout: timeoutMs }, () => {
      const c = sock.getPeerCertificate();
      sock.end();
      resolve(c && c.valid_to ? daysUntil(Date.parse(c.valid_to), now) : null);
    });
    sock.on('error', () => resolve(null));
    sock.on('timeout', () => { sock.destroy(); resolve(null); });
  });
}

async function domainDaysLeft(host, now, fetchImpl) {
  try {
    const j = await request('RDAP', `https://rdap.org/domain/${registrable(host)}`, { fetchImpl, timeoutMs: 8000, headers: { Accept: 'application/rdap+json' } });
    const ev = (j.events || []).find((e) => e.eventAction === 'expiration');
    return ev ? daysUntil(ev.eventDate, now) : null;
  } catch (_) { return null; }
}

/** Percent of checks that were up, per day for the last 7 days (oldest first). */
function uptimeSeries(history, now) {
  const days = lastDays(7, now);
  return days.map((d) => {
    const hs = history.filter((h) => ymd(h.at) === d);
    return hs.length ? (100 * hs.filter((h) => h.up).length) / hs.length : null;
  });
}

const source = {
  key: 'sites',
  label: 'Uptime checks',
  configured: (cfg) => Array.isArray(cfg.sites) && cfg.sites.length > 0,
  async pull(ctx) {
    const f = ctx.deps.fetch;
    const prev = (ctx.prev && ctx.prev.sites && ctx.prev.sites.data && ctx.prev.sites.data.history) || {};
    const history = {};
    const apps = []; const metrics = {}; const sites = [];
    await Promise.all(ctx.cfg.sites.map(async (s) => {
      const id = s.id || `site:${slug(s.name)}`;
      const host = hostOf(s.url);
      const t0 = Date.now();
      let up = false; let ms = null;
      try {
        const r = await (f || fetch)(s.url, { method: 'GET', redirect: 'follow', signal: AbortSignal.timeout(10000), headers: { 'User-Agent': 'VoidOS-Hub uptime check' } });
        up = r.status < 500; ms = Date.now() - t0;
      } catch (_) { up = false; }
      const hs = (prev[id] || []).filter((h) => ctx.now - h.at < 7 * DAY).concat([{ at: ctx.now, up }]);
      history[id] = hs;
      const series = uptimeSeries(hs, ctx.now);
      const known = series.filter((x) => x != null);
      const ssl = s.skipSsl ? null : (ctx.deps.sslDaysLeft || sslDaysLeft)(host, ctx.now);
      const dom = s.skipDomain ? null : (ctx.deps.domainDaysLeft || domainDaysLeft)(host, ctx.now, f);
      const [sslDays, domainDays] = await Promise.all([ssl, dom]);
      apps.push({ id, name: s.name, kind: 'site', platform: 'web', url: s.url, consoleUrl: s.console || '' });
      metrics[id] = {
        uptime: {
          today: series[6] != null ? series[6] : (up ? 100 : 0),
          week: known.length ? (100 * hs.filter((h) => h.up).length) / hs.length : null,
          series: series.map((x) => (x == null ? 100 : x)),
        },
      };
      sites.push({ appId: id, host, up, ms, sslDays, domainDays });
    }));
    return { apps, metrics, sites, history };
  },
};

module.exports = { source, registrable, uptimeSeries, slug };
