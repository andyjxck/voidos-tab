'use strict';
/** One synchronous BigQuery query over REST, as the function's service account. */
const { request, googleToken } = require('./http');

async function query(project, sql, deps = {}, params = []) {
  const token = deps.googleToken ? await deps.googleToken() : await googleToken(deps.fetch);
  const j = await request('BigQuery', `https://bigquery.googleapis.com/bigquery/v2/projects/${project}/queries`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}` },
    body: { query: sql, useLegacySql: false, timeoutMs: 25000, parameterMode: 'NAMED', queryParameters: params },
    fetchImpl: deps.fetch, timeoutMs: 30000,
  });
  if (!j.jobComplete) throw new Error('BigQuery: query took too long');
  const names = j.schema.fields.map((f) => f.name);
  return (j.rows || []).map((r) => Object.fromEntries(r.f.map((c, i) => [names[i], c.v])));
}

module.exports = { query };
