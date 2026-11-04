// Explicit opt-in live integration: real HTTP, existing client API and page handlers.
// Node adapts wx host APIs; this is NOT WeChat runtime/device verification.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const [envFile, base = 'http://127.0.0.1:8080', consent] = process.argv.slice(2);
if (!envFile || consent !== '--create-synthetic-data' || !/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) {
  console.error('Usage: node scripts/live-api.js /path/to/ignored/.env http://127.0.0.1:8080 --create-synthetic-data');
  process.exit(2);
}
const line = fs.readFileSync(envFile, 'utf8').split(/\r?\n/).find(s => s.startsWith('APP_OWNER_ACCESS_CODE='));
const code = line && line.slice(line.indexOf('=') + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
if (!code) { console.error('Missing APP_OWNER_ACCESS_CODE; credentials are never printed.'); process.exit(2); }
const report = {startedAt: new Date().toISOString(), transport: 'real HTTP through D lib/api.js', host: 'Node wx adapter; not WeChat', checks: [], jobs: [], requests: 0};
const storage = new Map();
let modalConfirm = false, lastCreation, lastNavigation, clipboard;
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, JSON.parse(JSON.stringify(value))), removeStorageSync: key => storage.delete(key),
  reLaunch: o => { lastNavigation = o.url; if (o.complete) o.complete(); }, navigateTo: o => { lastNavigation = o.url; },
  showModal: o => o.success({confirm: modalConfirm}), setClipboardData: o => { clipboard = o.data; if (o.success) o.success(); },
  request(options) {
    report.requests++;
    if (options.method === 'POST' && options.url.endsWith('/problems')) lastCreation = options;
    const req = http.request(options.url, {method: options.method, headers: options.header}, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        let data;
        try { data = JSON.parse(Buffer.concat(chunks).toString()); } catch { data = null; }
        if (options.url.includes('/jobs/') && data && ['succeeded', 'failed'].includes(data.status)) {
          if (!report.jobs.some(j => j.id === data.id)) report.jobs.push({id: data.id, kind: data.kind, status: data.status, mode: data.mode, errorCode: data.error && data.error.code});
        }
        options.success({statusCode: res.statusCode, data});
      });
    });
    req.setTimeout(options.timeout, () => req.destroy(new Error('timeout')));
    req.on('error', () => options.fail({errMsg: 'real HTTP transport failure'}));
    if (options.data !== undefined) req.write(JSON.stringify(options.data));
    req.end();
  }
};
const api = require('../lib/api');
function page(name) {
  let spec; global.Page = value => { spec = value; };
  require(path.join(root, 'pages', name, name + '.js'));
  return Object.assign({}, spec, {data: JSON.parse(JSON.stringify(spec.data)), _active: true, setData(patch) { Object.assign(this.data, patch); }});
}
function clean(p) { assert.equal(p.data.error, '', 'Page operation reported an error (details remain local).'); }
async function check(name, fn) {
  try { await fn(); report.checks.push({name, passed: true}); console.log('PASS ' + name); }
  catch (err) { report.checks.push({name, passed: false, code: err.code || 'ASSERTION'}); console.log('FAIL ' + name + ' [' + (err.code || 'ASSERTION') + ']'); throw err; }
}
async function run() {
  api.configure(base);
  await check('health and real-service configuration', async () => {
    const health = await api.request('/health', {public: true}); assert.equal(health.status, 'ok');
  });
  const login = page('login'); login.setData({apiBase: base, accessCode: code});
  await check('login page with real backend', async () => { await login.submit(); clean(login); assert.ok(api.loggedIn()); assert.equal(login.data.accessCode, ''); });
  await check('model configured', async () => { const status = await api.request('/status'); assert.equal(status.model.configured, true); });
  const problems = page('problems');
  const marker = 'D合成API验收-' + new Date().toISOString();
  problems.setData({title: marker, goal: '虚构读书计划每周不超过两小时', constraints: '仅工程合成数据，无个人资料', unknowns: '虚构章节能否拆分'});
  let pid;
  await check('problem page create and real idempotent replay', async () => {
    await problems.create(); clean(problems);
    pid = decodeURIComponent(lastNavigation.split('?id=')[1]); assert.ok(pid); report.problemId = pid;
    const original = lastCreation;
    const again = await api.request('/problems', {method: 'POST', data: original.data, key: original.header['Idempotency-Key']});
    assert.equal(again.id, pid);
    await assert.rejects(api.request('/problems', {method: 'POST', data: {...original.data, title: marker + '-conflict'}, key: original.header['Idempotency-Key']}), e => e.statusCode === 409);
    await problems.load(false); clean(problems);
  });
  const workspace = page('workspace'); workspace.onLoad({id: pid});
  const text = '此为虚构工程验收文章，不代表个人情况。方案甲每周需要六小时，但虚构目标每周最多两小时。方案乙分四次各二十分钟阅读，余下四十分钟讨论。适用条件是章节可以拆分，未知条件是章节长度。作者建议先核实时间预算与章节结构。';
  workspace.setData({tab: 'sources', sourceTitle: marker + '-文章', sourceText: text, sourceUrl: 'https://example.org/fictional-decision-test'});
  let sid;
  await check('source page submit, real Job and preserved text', async () => {
    await workspace.ingest(); clean(workspace); assert.equal(workspace.data.jobId, '');
    assert.equal(workspace.data.items.length, 1); sid = workspace.data.items[0].id;
    const source = await api.request('/sources/' + sid); assert.equal(source.text, text); assert.equal(source.originUrl, 'https://example.org/fictional-decision-test');
  });
  await check('source detail handler and clipboard content', async () => {
    const source = page('source'); source.onLoad({id: sid}); await source.load(); clean(source); source.copy(); assert.equal(clipboard, source.data.item.originUrl);
  });
  workspace.setData({tab: 'chat', content: '仅根据当前问题已有的虚构文章讨论两小时时间预算，列出未知，并引用逐字原文。不要代本人确认决定。', items: [], nextCursor: null});
  await check('chat page without sourceIds, real model and exact evidence', async () => {
    await workspace.send(); clean(workspace); assert.equal(workspace.data.jobId, '');
    const assistant = workspace.data.items.find(m => m.role === 'assistant');
    assert.ok(assistant); assert.equal(assistant.mode, 'real'); assert.ok(assistant.evidence.length);
    for (const ref of assistant.evidence) { assert.equal(ref.sourceId, sid); assert.ok(text.includes(ref.quote)); }
    report.evidenceCount = assistant.evidence.length;
    assert.ok(Array.isArray(assistant.uncertainties));
    const decisions = await api.request('/decisions' + api.query({problemId: pid})); assert.equal(decisions.items.length, 0);
  });
  await check('real cursor pagination and deduplication', async () => {
    const endpoint = '/problems/' + pid + '/messages';
    const first = await api.request(endpoint + api.query({limit: 1})); assert.equal(first.items.length, 1); assert.ok(first.nextCursor);
    const second = await api.request(endpoint + api.query({limit: 1, cursor: first.nextCursor}));
    const merged = api.merge(first.items, second.items); assert.equal(merged.length, 2); assert.equal(api.merge(merged, second.items).length, 2);
  });
  await check('discussion page real read', async () => { workspace.setData({tab: 'discussions', items: []}); await workspace.load(false); clean(workspace); assert.ok(Array.isArray(workspace.data.items)); report.discussionCount = workspace.data.items.length; });
  await check('cancelled decision writes nothing; confirmed synthetic decision persists', async () => {
    workspace.setData({tab: 'decisions', items: [], choice: '合成验收：暂选虚构方案乙', rationale: '合成的两小时时间约束', expectedOutcome: '验证原理由与复盘分别保存'});
    modalConfirm = false; await workspace.decide(); clean(workspace);
    assert.equal((await api.request('/decisions' + api.query({problemId: pid}))).items.length, 0);
    modalConfirm = true; await workspace.decide(); clean(workspace); assert.equal(workspace.data.items.length, 1);
  });
  await check('review page appends and preserves original rationale', async () => {
    const decision = workspace.data.items[0];
    workspace.selectReview({currentTarget: {dataset: {id: decision.id}}});
    workspace.setData({outcome: '合成验收完成，不是真实生活结果', reflection: '应保留当时理由，复盘作为追加记录'});
    await workspace.review(); clean(workspace);
    const actual = workspace.data.items[0]; assert.equal(actual.rationale, decision.rationale); assert.equal(actual.reviews.length, 1);
  });
  await check('missing Job recovery unlocks further submissions', async () => {
    workspace.setData({jobId: api.uuid()}); api.rememberJob(pid, {id: workspace.data.jobId, kind: 'chat'});
    await workspace.resumeJob(); assert.equal(workspace.data.jobId, ''); assert.equal(api.pendingJob(pid), undefined); assert.ok(workspace.data.error);
  });
  await check('real 401 clears session and returns to login', async () => {
    storage.set('decision.session.v1', {accessToken: 'invalid-synthetic-token', expiresAt: new Date(Date.now() + 60000).toISOString()}); api.restore();
    await assert.rejects(api.request('/problems'), e => e.statusCode === 401);
    assert.equal(api.loggedIn(), false); assert.equal(lastNavigation, '/pages/login/login');
  });
  assert.ok(report.jobs.length >= 2); assert.ok(report.jobs.every(j => j.mode === 'real' && j.status === 'succeeded'));
}
run().catch(() => { process.exitCode = 1; }).finally(() => {
  api.logout(false);
  report.finishedAt = new Date().toISOString(); report.passed = !process.exitCode;
  const out = path.join(root, '.local'); fs.mkdirSync(out, {recursive: true, mode: 0o700});
  const dest = path.join(out, 'live-api-' + report.startedAt.replace(/[:.]/g, '-') + '.json');
  fs.writeFileSync(dest, JSON.stringify(report, null, 2) + '\n', {mode: 0o600});
  console.log('Sanitized local report: ' + dest);
});
