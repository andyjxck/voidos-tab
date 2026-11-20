'use strict';
/**
 * Crashlytics crashes per app, from the Crashlytics BigQuery export: crashes per day for 7 days,
 * and today's crash groups, marked fresh when the group was first seen in the last 24 hours.
 * Table names follow Firebase's pattern: firebase_crashlytics.<bundle_id_with_underscores>_IOS.
 * https://firebase.google.com/docs/crashlytics/bigquery-export
 */
const { query } = require('../bigquery');
const { lastDays } = require('../dates');

const tableFor = (bundleId, platform = 'IOS') => `${bundleId.replace(/[.-]/g, '_')}_${platform}`;
const cfgOf = (cfg) => cfg.crashlytics || {};

const source = {
  key: 'crashlytics',
  label: 'Crashlytics',
  configured: (cfg) => !!cfgOf(cfg).project,
  async pull(ctx) {
    const c = cfgOf(ctx.cfg);
    const ds = `${c.project}.${c.dataset || 'firebase_crashlytics'}`;
    const days = lastDays(7, ctx.now);
    const apps = (c.apps && c.apps.length ? c.apps : ctx.apps.filter((a) => a.bundleId).map((a) => ({ bundleId: a.bundleId })));
    const metrics = {}; const out = []; const errors = [];
    await Promise.all(apps.map(async (a) => {
      const id = a.appId || (ctx.apps.find((x) => x.bundleId === a.bundleId) || {}).id;
      if (!id) return;
      const table = `\`${ds}.${a.table || tableFor(a.bundleId, a.platform)}\``;
      try {
        const perDay = await query(c.project, `SELECT FORMAT_DATE('%F', DATE(event_timestamp)) AS d, COUNT(*) AS n
          FROM ${table} WHERE event_timestamp >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 7 DAY) AND is_fatal
          GROUP BY d`, ctx.deps);
        const series = days.map((d) => Number((perDay.find((r) => r.d === d) || {}).n || 0));
        const groups = await query(c.project, `SELECT issue_title AS title, ANY_VALUE(issue_subtitle) AS subtitle, COUNT(*) AS events,
            COUNT(DISTINCT installation_uuid) AS users, MAX(application.display_version) AS version,
            (SELECT MIN(event_timestamp) FROM ${table} t2 WHERE t2.issue_id = t.issue_id
               AND t2.event_timestamp >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY)) AS first
          FROM ${table} t WHERE event_timestamp >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 1 DAY) AND is_fatal
          GROUP BY issue_id, issue_title ORDER BY events DESC LIMIT 5`, ctx.deps);
        metrics[id] = { crashes: { today: series[6], week: series.reduce((x, y) => x + y, 0), series } };
        out.push({
          id,
          crashes: groups.map((g) => ({
            title: g.title || 'Crash', subtitle: g.subtitle || '', events: Number(g.events) || 0, users: Number(g.users) || 0,
            version: g.version || '', fresh: ctx.now - Number(g.first) * 1000 < 86400000,
          })),
        });
      } catch (e) {
        // no table yet just means no crash has been exported for that app
        if (/Not found: Table/i.test(e.message)) metrics[id] = { crashes: { today: 0, week: 0, series: Array(7).fill(0) } };
        else errors.push(e.message);
      }
    }));
    if (errors.length && !Object.keys(metrics).length) throw new Error(errors[0]);
    return { metrics, apps: out };
  },
};

module.exports = { source, tableFor };
