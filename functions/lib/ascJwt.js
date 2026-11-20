'use strict';
/**
 * The App Store Connect API token: an ES256 JWT signed with the user's .p8 key, valid 20 minutes
 * (Apple's limit). Node's crypto signs it; no JWT library needed.
 * https://developer.apple.com/documentation/appstoreconnectapi/generating-tokens-for-api-requests
 */
const crypto = require('crypto');

const b64url = (buf) => Buffer.from(buf).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');

function ascToken({ keyId, issuerId, privateKey }, nowMs = Date.now()) {
  if (!keyId || !issuerId || !privateKey) throw new Error('App Store Connect: key not set');
  const iat = Math.floor(nowMs / 1000);
  const header = { alg: 'ES256', kid: keyId, typ: 'JWT' };
  const payload = { iss: issuerId, iat, exp: iat + 19 * 60, aud: 'appstoreconnect-v1' };
  const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
  // secrets pasted into a console often lose their newlines: put the PEM back together
  const pem = normalisePem(privateKey);
  const sig = crypto.sign('sha256', Buffer.from(input), { key: pem, dsaEncoding: 'ieee-p1363' });
  return `${input}.${b64url(sig)}`;
}

function normalisePem(k) {
  let s = String(k).trim().replace(/\\n/g, '\n');
  if (!s.includes('\n')) {
    const body = s.replace('-----BEGIN PRIVATE KEY-----', '').replace('-----END PRIVATE KEY-----', '').replace(/\s+/g, '');
    s = `-----BEGIN PRIVATE KEY-----\n${body.match(/.{1,64}/g).join('\n')}\n-----END PRIVATE KEY-----`;
  }
  return s;
}

module.exports = { ascToken, normalisePem };
