'use strict';
/** Date helpers in UTC (the store reports are UTC days). */

const DAY = 86400000;
/** 2.0.2: how many days of daily figures the hub keeps, so the tablet can show any range up to a year. */
const HISTORY_DAYS = 365;

function ymd(ms) { return new Date(ms).toISOString().slice(0, 10); }

/** The last [n] UTC days ending [endMs]'s day, oldest first, as YYYY-MM-DD. */
function lastDays(n, endMs) {
  const out = [];
  for (let i = n - 1; i >= 0; i--) out.push(ymd(endMs - i * DAY));
  return out;
}

/** Whole days from [now] until the date [then] (negative once it has passed). */
function daysUntil(then, now) {
  const t = typeof then === 'number' ? then : Date.parse(then);
  if (Number.isNaN(t)) return null;
  return Math.floor((t - now) / DAY);
}

module.exports = { DAY, HISTORY_DAYS, ymd, lastDays, daysUntil };
