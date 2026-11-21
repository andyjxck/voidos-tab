'use strict';
/**
 * GitHub: each repo's latest commit and its latest Actions run. The repos are config.github.repos,
 * or else your six most recently pushed. A fine-grained token with read access to Contents and
 * Actions is enough. https://docs.github.com/en/rest
 */
const { request } = require('../http');

const source = {
  key: 'github',
  label: 'GitHub',
  configured: (cfg) => !!(cfg.github && cfg.github.token),
  async pull(ctx) {
    const c = ctx.cfg.github; const f = ctx.deps.fetch;
    const h = { Authorization: `Bearer ${c.token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'voidos-hub' };
    const get = (p) => request('GitHub', `https://api.github.com${p}`, { headers: h, fetchImpl: f });
    let repos = c.repos;
    if (!repos || !repos.length) repos = (await get('/user/repos?sort=pushed&per_page=6')).map((r) => r.full_name);
    const out = await Promise.all(repos.map(async (r) => {
      const [commits, runs, rels] = await Promise.all([
        get(`/repos/${r}/commits?per_page=1`),
        get(`/repos/${r}/actions/runs?per_page=1`).catch(() => ({ workflow_runs: [] })),
        // 2.0.2: the newest releases, for the calendar
        get(`/repos/${r}/releases?per_page=5`).catch(() => []),
      ]);
      const cm = commits[0];
      const run = runs.workflow_runs && runs.workflow_runs[0];
      return {
        repo: r,
        message: cm ? cm.commit.message.split('\n')[0].slice(0, 120) : '',
        sha: cm ? cm.sha.slice(0, 7) : '',
        at: cm ? Date.parse(cm.commit.committer.date) : 0,
        build: run ? (run.status === 'completed' ? run.conclusion || 'completed' : run.status) : '',
        url: cm ? cm.html_url : `https://github.com/${r}`,
        buildAt: run ? Date.parse(run.updated_at || run.created_at || '') || 0 : 0,
        releases: (Array.isArray(rels) ? rels : []).filter((x) => !x.draft).map((x) => ({ tag: x.tag_name || x.name || '', at: Date.parse(x.published_at || x.created_at || '') || 0 })).filter((x) => x.tag && x.at),
      };
    }));
    return { repos: out.sort((a, b) => b.at - a.at) };
  },
};

module.exports = { source };
