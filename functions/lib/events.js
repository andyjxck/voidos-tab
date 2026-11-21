'use strict';
/**
 * 2.0.2, the hub calendar: every dated thing the hub knows, as one list of events the tablet's
 * Calendar screen shows, upcoming and past.
 *
 * An event is { id, at, kind, appId, title, detail, estimated }:
 *   at        ms since epoch (a date-only event sits at 12:00 UTC on its day)
 *   kind      release | scheduled | pending | phased | review | payout | renewal | ssl | domain | build | cost
 *   appId     the hub app it belongs to ('' when it belongs to none: the payout, the renewal)
 *   estimated true when the date is worked out, not reported (the payout estimate, SSL and domain)
 */
const DAY = 86400000;
const KEEP_PAST_MS = 400 * DAY;
const KEEP_FUTURE_MS = 400 * DAY;
const MAX_EVENTS = 300;

/** Readable words for App Store Connect's version states. */
const STATE_WORDS = {
  PREPARE_FOR_SUBMISSION: 'Preparing',
  WAITING_FOR_REVIEW: 'Waiting for review',
  IN_REVIEW: 'In review',
  PENDING_DEVELOPER_RELEASE: 'Approved, waiting for you',
  PENDING_APPLE_RELEASE: 'Approved, releasing',
  PROCESSING_FOR_APP_STORE: 'Processing',
  PROCESSING_FOR_DISTRIBUTION: 'Processing',
  READY_FOR_SALE: 'Live',
  READY_FOR_DISTRIBUTION: 'Live',
  ACCEPTED: 'Approved',
  REJECTED: 'Rejected',
  METADATA_REJECTED: 'Metadata rejected',
  DEVELOPER_REJECTED: 'Pulled by you',
  REMOVED_FROM_SALE: 'Removed from sale',
  DEVELOPER_REMOVED_FROM_SALE: 'Removed from sale',
  INVALID_BINARY: 'Invalid binary',
  REPLACED_WITH_NEW_VERSION: 'Replaced',
  WAITING_FOR_EXPORT_COMPLIANCE: 'Export compliance',
};
const stateWords = (s) => STATE_WORDS[s] || String(s || '').toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());

const PENDING = new Set(['PENDING_DEVELOPER_RELEASE', 'PENDING_APPLE_RELEASE', 'ACCEPTED']);
const LIVE = new Set(['READY_FOR_SALE', 'READY_FOR_DISTRIBUTION']);
const REVIEW = new Set(['WAITING_FOR_REVIEW', 'IN_REVIEW']);

/** A 'YYYY-MM-DD' (or a full ISO time) as ms; a bare date sits at 12:00 UTC so it reads as that day everywhere in Europe. */
function dateMs(v) {
  if (v == null || v === '') return 0;
  if (typeof v === 'number') return v;
  const s = String(v);
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return Date.parse(`${s}T12:00:00Z`) || 0;
  return Date.parse(s) || 0;
}

/**
 * The events one app's App Store versions give: the live version's release (from the public
 * listing), scheduled and pending releases, phased releases, and the version in review.
 *
 * @param appId    the hub id (asc:123)
 * @param versions ASC appStoreVersions data rows (attributes + optional phased: { phasedReleaseState, startDate, currentDayNumber })
 * @param listing  { version, currentVersionReleaseDate, releaseDate } from the iTunes lookup, or null
 */
function versionEvents(appId, versions, listing) {
  const out = [];
  const live = listing && listing.version;
  if (listing && listing.currentVersionReleaseDate) {
    out.push(ev(appId, 'release', dateMs(listing.currentVersionReleaseDate), live ? `${live} released` : 'Released', 'Live on the App Store'));
  }
  if (listing && listing.releaseDate && listing.releaseDate !== listing.currentVersionReleaseDate) {
    out.push(ev(appId, 'release', dateMs(listing.releaseDate), 'First release', 'Launched on the App Store'));
  }
  for (const row of versions || []) {
    const a = (row && row.attributes) || {};
    const v = a.versionString || '';
    const state = a.appVersionState || a.appStoreState || '';
    const created = dateMs(a.createdDate);
    const earliest = dateMs(a.earliestReleaseDate);
    if (a.releaseType === 'SCHEDULED' && earliest && !LIVE.has(state)) {
      out.push(ev(appId, 'scheduled', earliest, `${v} scheduled`, stateWords(state)));
    } else if (PENDING.has(state)) {
      out.push(ev(appId, 'pending', earliest || created, `${v} approved`, stateWords(state)));
    } else if (REVIEW.has(state) || state === 'REJECTED' || state === 'METADATA_REJECTED') {
      out.push(ev(appId, 'review', created, `${v} ${stateWords(state).toLowerCase()}`, 'Submitted'));
    } else if (LIVE.has(state) && v !== live && created) {
      // an older live version the listing no longer shows: its created date is all ASC gives
      out.push(ev(appId, 'release', created, `${v} created`, stateWords(state)));
    }
    const ph = row.phased;
    if (ph && ph.startDate) {
      const day = ph.currentDayNumber ? `day ${ph.currentDayNumber} of 7` : stateWords(ph.phasedReleaseState);
      out.push(ev(appId, 'phased', dateMs(ph.startDate), `${v} phased release`, day));
    }
  }
  return out;
}

/**
 * The review-status log: one entry each time an app's newest version changes state, so the
 * calendar can show the history. Pure: [prevLog] plus a line for each app whose state moved.
 */
function logReviews(prevLog, apps, now) {
  const log = Array.isArray(prevLog) ? prevLog.slice() : [];
  for (const a of apps || []) {
    if (!a.review || !a.review.state) continue;
    const last = [...log].reverse().find((x) => x.appId === a.id);
    if (last && last.state === a.review.state && last.version === a.review.version) continue;
    log.push({ appId: a.id, version: a.review.version || '', state: a.review.state, at: last ? now : (a.review.since || now) });
  }
  return log.filter((x) => now - x.at <= KEEP_PAST_MS).slice(-200);
}

function reviewLogEvents(log) {
  return (log || []).map((x) => ev(x.appId, 'review', x.at, `${x.version ? `${x.version} ` : ''}${stateWords(x.state).toLowerCase()}`, 'Review status', false, `log:${x.appId}:${x.version}:${x.state}`));
}

function ev(appId, kind, at, title, detail, estimated = false, id = '') {
  return { id: id || `${kind}:${appId}:${at}:${title}`, at, kind, appId: appId || '', title, detail: detail || '', estimated: !!estimated };
}

/**
 * Every event for the summary: the sources' own events plus the dates the summary already holds
 * (the payout, costs with a due date, SSL and domain expiry, GitHub builds). Sorted by date, the
 * newest first, deduplicated, and kept to roughly a year either side of now.
 *
 * @param out       the summary being built (payout, costs, sites, repos)
 * @param extra     events the sources returned
 * @param cfg       hub config (appstore.nextPayment marks the payout as a real date)
 * @param sitesAt   when the sites were last checked (the SSL and domain days count from then)
 */
function buildEvents(out, extra, cfg, now, sitesAt) {
  const list = [...(extra || [])];
  if (out.payout && out.payout.nextDate) {
    const real = !!(cfg && cfg.appstore && cfg.appstore.nextPayment);
    const amt = out.payout.estimate ? `${out.payout.currency || ''} ${Number(out.payout.estimate).toFixed(2)}`.trim() : '';
    list.push(ev('', 'payout', dateMs(out.payout.nextDate), real ? 'Apple payout' : 'Apple payout (est.)', amt, !real, `payout:${out.payout.nextDate}`));
  }
  for (const c of out.costs || []) {
    if (!c.due) continue;
    const kind = c.key === 'apple' ? 'renewal' : 'cost';
    list.push(ev('', kind, dateMs(c.due), c.key === 'apple' ? 'Apple Developer renewal' : `${c.label || 'Cost'} due`,
      c.amount != null ? `${c.currency || ''} ${Number(c.amount).toFixed(2)}`.trim() : '', false, `${kind}:${c.key}:${c.due}`));
  }
  const base = sitesAt || now;
  for (const s of out.sites || []) {
    const day = (n) => Math.floor((base + n * DAY) / DAY) * DAY + DAY / 2;
    if (typeof s.sslDays === 'number') list.push(ev(s.appId, 'ssl', day(s.sslDays), 'SSL certificate expires', s.host || '', true, `ssl:${s.appId}`));
    if (typeof s.domainDays === 'number') list.push(ev(s.appId, 'domain', day(s.domainDays), 'Domain expires', s.host || '', true, `domain:${s.appId}`));
  }
  for (const r of out.repos || []) {
    for (const b of r.releases || []) {
      if (b.at) list.push(ev('', 'build', b.at, `${r.repo.split('/').pop()} ${b.tag}`, 'GitHub release', false, `gh:${r.repo}:${b.tag}`));
    }
    if (r.buildAt && r.build) list.push(ev('', 'build', r.buildAt, `${r.repo.split('/').pop()} build ${r.build}`, r.sha ? `commit ${r.sha}` : 'GitHub Actions', false, `ghrun:${r.repo}`));
  }
  const seen = new Set();
  return list
    .filter((e) => e.at && now - e.at <= KEEP_PAST_MS && e.at - now <= KEEP_FUTURE_MS)
    .filter((e) => (seen.has(e.id) ? false : (seen.add(e.id), true)))
    .sort((a, b) => b.at - a.at)
    .slice(0, MAX_EVENTS);
}

module.exports = { versionEvents, logReviews, reviewLogEvents, buildEvents, stateWords, dateMs };
