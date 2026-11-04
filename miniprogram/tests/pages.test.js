const {test} = require('node:test');
const assert = require('node:assert/strict');
function setup() {
  delete require.cache[require.resolve('../lib/api')]; delete require.cache[require.resolve('../lib/page')];
  const api = require('../lib/api');
  let spec;
  global.Page = p => { spec = p; };
  global.wx = {showModal: o => o.success({confirm: false})};
  delete require.cache[require.resolve('../pages/workspace/workspace')];
  require('../pages/workspace/workspace');
  const page = Object.assign({}, spec, {data: {...spec.data, problemId: 'p'}, _active: true, setData(patch) { Object.assign(this.data, patch); }});
  return {api, page};
}
test('decision requires explicit confirmation; cancellation creates nothing', async () => {
  const {api, page} = setup(); let calls = 0;
  api.create = async () => { calls++; };
  page.setData({choice: 'fictional choice', rationale: 'fictional reason', expectedOutcome: 'fictional outcome'});
  await page.decide(); assert.equal(calls, 0); assert.equal(page.data.busy, false);
  global.wx.showModal = o => o.success({confirm: true});
  page.load = async () => {};
  await page.decide(); assert.equal(calls, 1); assert.equal(page.data.choice, '');
});
test('source submission preserves pasted text and original link, no new fields', async () => {
  const {api, page} = setup(); let captured;
  api.create = async (path, body) => { captured = {path, body}; return {id: 'j', status: 'queued', mode: 'real'}; };
  api.rememberJob = () => {}; page.resumeJob = async () => {};
  page.setData({sourceText: 'Fictional article', sourceUrl: 'https://example.test/article', sourceTitle: 'Example'});
  await page.ingest();
  assert.deepEqual(captured, {path: '/sources', body: {problemId: 'p', kind: 'article', title: 'Example', text: 'Fictional article', url: 'https://example.test/article'}});
  assert.equal(page.data.jobId, 'j');
  captured = null; await page.ingest(); assert.equal(captured, null);
});
test('unload cancels polling; a failed job releases submit lock', async () => {
  const {api, page} = setup(); page._control = {}; page.onUnload(); assert.equal(page._control.cancelled, true);
  page._active = true; page.setData({jobId: 'j'});
  api.poll = async () => { throw Object.assign(new Error('failed'), {code: 'JOB_FAILED'}); };
  api.rememberJob = () => {};
  await page.resumeJob(); assert.equal(page.data.jobId, ''); assert.equal(page.data.polling, false); assert.equal(page.data.error, 'failed');
});
test('missing job releases persistent submit lock; network timeout retains it', async () => {
  const {api, page} = setup(); const saved = [];
  page.setData({jobId: 'missing'}); api.rememberJob = (id, value) => saved.push(value);
  api.poll = async () => { throw Object.assign(new Error('not found'), {statusCode: 404, code: 'NOT_FOUND'}); };
  await page.resumeJob(); assert.equal(page.data.jobId, ''); assert.deepEqual(saved, [null]);
  page.setData({jobId: 'still-running'});
  api.poll = async () => { throw Object.assign(new Error('waiting'), {code: 'POLL_TIMEOUT'}); };
  await page.resumeJob(); assert.equal(page.data.jobId, 'still-running'); assert.deepEqual(saved, [null]);
});
