'use strict';
/**
 * Small HTTP helpers on Node's own fetch: a timeout on every call, JSON in and out, and errors that
 * say which service failed and how, never the request's secrets.
 */

class HttpError extends Error {
  constructor(service, status, body) {
    super(`${service}: HTTP ${status}${body ? ` ${String(body).slice(0, 160)}` : ''}`);
    this.status = status;
  }
}

async function request(service, url, { method = 'GET', headers = {}, body, timeoutMs = 20000, raw = false, fetchImpl } = {}) {
  const f = fetchImpl || globalThis.fetch;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const init = { method, headers: { ...headers }, signal: ctl.signal };
    if (body !== undefined) {
      if (typeof body === 'string' || body instanceof URLSearchParams) init.body = body;
      else { init.body = JSON.stringify(body); init.headers['Content-Type'] = init.headers['Content-Type'] || 'application/json'; }
    }
    const r = await f(url, init);
    if (!r.ok) {
      let text = '';
      try { text = await r.text(); } catch (_) { /* nothing more to say */ }
      // Apple and Google put a short reason in errors[0].detail / error.message
      try {
        const j = JSON.parse(text);
        text = (j.errors && j.errors[0] && (j.errors[0].detail || j.errors[0].title)) || (j.error && (j.error.message || j.error)) || j.message || '';
      } catch (_) { text = ''; }
      throw new HttpError(service, r.status, text);
    }
    if (raw) return Buffer.from(await r.arrayBuffer());
    if (r.status === 204) return null;
    const text = await r.text();
    return text ? JSON.parse(text) : null;
  } catch (e) {
    if (e.name === 'AbortError') throw new Error(`${service}: timed out`);
    throw e;
  } finally {
    clearTimeout(t);
  }
}

/**
 * An OAuth access token for Google APIs as the function's own service account, from the metadata
 * server Cloud Functions runs beside. No key file: the account's IAM roles decide what it can read.
 */
async function googleToken(fetchImpl) {
  const j = await request('Google metadata', 'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token', {
    headers: { 'Metadata-Flavor': 'Google' }, timeoutMs: 5000, fetchImpl,
  });
  return j.access_token;
}

/** A Google access token from an OAuth refresh token (AdMob has no service-account access). */
async function googleRefreshToken({ clientId, clientSecret, refreshToken }, fetchImpl) {
  const j = await request('Google OAuth', 'https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: clientId, client_secret: clientSecret, refresh_token: refreshToken, grant_type: 'refresh_token' }),
    fetchImpl,
  });
  return j.access_token;
}

module.exports = { request, HttpError, googleToken, googleRefreshToken };
