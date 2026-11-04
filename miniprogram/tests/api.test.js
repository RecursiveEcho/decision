const {test} = require('node:test');
const assert = require('node:assert/strict');
function harness() {
  const storage = new Map();
  const calls = [], redirects = [];
  let respond;
  global.wx = {
    getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v), removeStorageSync: k => storage.delete(k),
    reLaunch: o => { redirects.push(o.url); if (o.complete) o.complete(); },
    request: options => { calls.push(options); respond(options); }
  };
  delete require.cache[require.resolve('../lib/api')];
  const api = require('../lib/api');
  api.configure('https://example.test');
  return {api, calls, redirects, storage, respond: fn => { respond = fn; }, async login() {
    respond = o => o.success({statusCode: 200, data: {accessToken: 'synthetic-token', expiresAt: new Date(Date.now() + 3600000).toISOString()}});
    await api.login('fictional-code'); calls.length = 0;
  }};
}
test('configuration rejects credential URLs and nonlocal plaintext', () => {
  const h = harness();
  for (const url of ['http://example.test', 'https://user:secret@example.test', 'https://example.test/api/v1', 'javascript:alert(1)']) assert.throws(() => h.api.configure(url));
  h.api.configure('http://127.0.0.1:8080/'); assert.equal(h.api.base(), 'http://127.0.0.1:8080');
});
test('concurrent POST is one request; uncertain retry retains its UUID', async () => {
  const h = harness(); await h.login();
  let pending;
  h.respond(o => { pending = o; });
  const a = h.api.create('/problems', {title: 'fictional'});
  const b = h.api.create('/problems', {title: 'fictional'});
  assert.equal(a, b); assert.equal(h.calls.length, 1);
  const key = pending.header['Idempotency-Key'];
  assert.match(key, /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  pending.fail(); await assert.rejects(a, {code: 'NETWORK'});
  h.respond(o => o.success({statusCode: 201, data: {id: 'created'}}));
  await h.api.create('/problems', {title: 'fictional'});
  assert.equal(h.calls[1].header['Idempotency-Key'], key);
  await h.api.create('/problems', {title: 'fictional'});
  assert.notEqual(h.calls[2].header['Idempotency-Key'], key);
});
test('401 clears token and pending jobs, redirects, never retries model', async () => {
  const h = harness(); await h.login(); h.api.rememberJob('p', {id: 'j', kind: 'chat'});
  h.respond(o => o.success({statusCode: 401, data: {error: {code: 'EXPIRED', message: 'expired'}}}));
  await assert.rejects(h.api.request('/status'), {code: 'EXPIRED'});
  assert.equal(h.api.loggedIn(), false); assert.equal(h.api.pendingJob('p'), null);
  assert.equal(h.redirects.length, 1); assert.equal(h.calls.length, 1);
});
test('poll terminates on success with mock marker unchanged', async () => {
  const h = harness(); await h.login(); let n = 0;
  h.respond(o => o.success({statusCode: 200, data: {id: 'j', status: ++n === 2 ? 'succeeded' : 'running', mode: 'mock'}}));
  const job = await h.api.poll('j', {}, null, {sleep: async () => {}});
  assert.equal(job.mode, 'mock'); assert.equal(n, 2); assert.ok(h.calls.every(x => x.method === 'GET'));
});
test('poll cancellation blocks further requests; timeout does not resubmit', async () => {
  const h = harness(); await h.login(); const control = {};
  h.respond(o => o.success({statusCode: 200, data: {status: 'running'}}));
  await assert.rejects(h.api.poll('j', control, null, {sleep: async () => { control.cancelled = true; }}), {code: 'CANCELLED'});
  assert.equal(h.calls.length, 1);
  await assert.rejects(h.api.poll('j', {}, null, {attempts: 2, sleep: async () => {}}), {code: 'POLL_TIMEOUT'});
  assert.equal(h.calls.length, 3);
});
test('transient poll failures back off; failed jobs surface real error', async () => {
  const h = harness(); await h.login(); const sleeps = []; let n = 0;
  h.respond(o => { if (++n < 3) o.fail(); else o.success({statusCode: 200, data: {status: 'failed', error: {message: 'model unavailable'}}}); });
  await assert.rejects(h.api.poll('j', {}, null, {sleep: async ms => sleeps.push(ms)}), {code: 'JOB_FAILED', message: 'model unavailable'});
  assert.deepEqual(sleeps, [2000, 4000]);
});
test('cursor encoding and dedup preserve updated records', () => {
  const h = harness(); assert.equal(h.api.query({cursor: 'a+b/=', limit: 30}), '?cursor=a%2Bb%2F%3D&limit=30');
  assert.deepEqual(h.api.merge([{id: 'a', v: 1}], [{id: 'a', v: 2}, {id: 'b'}]), [{id: 'a', v: 2}, {id: 'b'}]);
});
test('base change clears session so credentials cannot leak to another server', async () => {
  const h = harness(); await h.login(); h.api.configure('https://other.example.test');
  assert.equal(h.api.loggedIn(), false);
  await assert.rejects(h.api.request('/sources'), {code: 'AUTH'}); assert.equal(h.calls.length, 0);
});
test('unsupported PATCH is rejected without a fake method override', async () => {
  const h = harness(); await h.login();
  await assert.rejects(h.api.request('/discussions/d', {method: 'PATCH', data: {status: 'read'}}), {code: 'UNSUPPORTED_METHOD'});
  assert.equal(h.calls.length, 0);
});
