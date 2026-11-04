// No model key, mock fallback, observer control or embedded owner credentials.
let session = null;
let redirecting = false;
const intents = new Map();
const SESSION = 'decision.session.v1';
const BASE = 'decision.apiBase.v1';
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const n = Math.floor(Math.random() * 16);
    return (c === 'x' ? n : (n & 3) | 8).toString(16);
  });
}
function base() { return wx.getStorageSync(BASE) || ''; }
function configure(value) {
  const url = value.trim().replace(/\/+$/, '');
  if (!/^https:\/\/[a-z0-9.-]+(?::\d+)?(?:\/[a-z0-9_./-]*)?$/i.test(url) &&
      !/^http:\/\/(localhost|127\.0\.0\.1)(?::\d+)?$/i.test(url)) {
    throw new Error('请输入 HTTPS 服务地址；仅本机开发允许 http://localhost 或 127.0.0.1。');
  }
  if (url.endsWith('/api/v1')) throw new Error('填写服务根地址，不要包含 /api/v1。');
  if (url !== base()) logout(false);
  wx.setStorageSync(BASE, url);
}
function restore() { session = wx.getStorageSync(SESSION) || null; }
function loggedIn() { return !!(session && session.accessToken && Date.parse(session.expiresAt) > Date.now()); }
function logout(navigate = true) {
  session = null;
  intents.clear();
  wx.removeStorageSync(SESSION);
  if (navigate && !redirecting) {
    redirecting = true;
    wx.reLaunch({url: '/pages/login/login', complete() { redirecting = false; }});
  }
}
function requireLogin() { if (loggedIn()) return true; logout(); return false; }
function error(message, code, retryable = false) { return Object.assign(new Error(message), {code, retryable}); }
function request(path, options = {}) {
  const {method = 'GET', data, key, public: isPublic = false} = options;
  if (!['GET', 'POST', 'PUT', 'DELETE', 'HEAD', 'OPTIONS'].includes(method)) return Promise.reject(error('此操作暂不支持，请在网页完成。', 'UNSUPPORTED_METHOD'));
  if (!base()) return Promise.reject(error('请先配置 API 服务地址。', 'CONFIG'));
  if (!isPublic && !loggedIn()) { logout(); return Promise.reject(error('登录已过期，请重新登录。', 'AUTH')); }
  return new Promise((resolve, reject) => {
    wx.request({url: base() + '/api/v1' + path, method, data, timeout: 20000, redirect: 'manual',
      header: Object.assign({'Content-Type': 'application/json'},
        !isPublic ? {Authorization: 'Bearer ' + session.accessToken} : {},
        method === 'POST' ? {'Idempotency-Key': key || uuid()} : {}),
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) return resolve(res.data);
        if (res.statusCode === 401 && !isPublic) logout();
        const detail = (res.data && res.data.error) || {};
        reject(Object.assign(error(detail.message || '请求失败（' + res.statusCode + '）', detail.code || String(res.statusCode), !!detail.retryable || res.statusCode >= 500), {statusCode: res.statusCode}));
      },
      fail() { reject(error('网络请求失败。请检查地址、网络与小程序合法域名；重试会沿用本次提交标识。', 'NETWORK', true)); }
    });
  });
}
async function login(accessCode) {
  const result = await request('/auth/login', {method: 'POST', data: {accessCode}, public: true});
  if (!result.accessToken || !(Date.parse(result.expiresAt) > Date.now())) throw error('服务端返回了无效登录信息。', 'INVALID_SESSION');
  session = {accessToken: result.accessToken, expiresAt: result.expiresAt};
  wx.setStorageSync(SESSION, session);
}
// Retain a key on uncertain network/server failure; share concurrent requests.
function create(path, data) {
  const signature = base() + path + JSON.stringify(data);
  let intent = intents.get(signature);
  if (!intent) { intent = {key: uuid()}; intents.set(signature, intent); }
  if (intent.promise) return intent.promise;
  intent.promise = request(path, {method: 'POST', data, key: intent.key}).then(result => {
    intents.delete(signature); return result;
  }).catch(err => {
    intent.promise = null;
    if (!err.retryable) intents.delete(signature);
    throw err;
  });
  return intent.promise;
}
function pendingJob(problemId) { return session && session.jobs && session.jobs[problemId]; }
function rememberJob(problemId, job) {
  if (!session) return;
  session.jobs = session.jobs || {};
  if (job) session.jobs[problemId] = job; else delete session.jobs[problemId];
  wx.setStorageSync(SESSION, session);
}
function query(params) {
  const pairs = Object.keys(params).filter(k => params[k] !== undefined && params[k] !== null && params[k] !== '');
  return pairs.length ? '?' + pairs.map(k => encodeURIComponent(k) + '=' + encodeURIComponent(params[k])).join('&') : '';
}
function merge(items, more) {
  const map = new Map(items.map(item => [item.id, item]));
  more.forEach(item => map.set(item.id, item));
  return Array.from(map.values());
}
async function poll(id, control, progress, options = {}) {
  const sleep = options.sleep || (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const attempts = options.attempts || 90;
  let delay = 2000;
  for (let i = 0; i < attempts; i++) {
    if (control.cancelled) throw error('已暂停查询。', 'CANCELLED');
    let job;
    try { job = await request('/jobs/' + encodeURIComponent(id)); delay = 2000; }
    catch (err) {
      if (!err.retryable) throw err;
      if (progress && !control.cancelled) progress(null, err.message);
      await sleep(delay); delay = Math.min(delay * 2, 15000); continue;
    }
    if (control.cancelled) throw error('已暂停查询。', 'CANCELLED');
    if (progress) progress(job);
    if (job.status === 'succeeded') return job;
    if (job.status === 'failed') throw error((job.error && job.error.message) || '处理失败，可重新提交。', 'JOB_FAILED');
    if (!['queued', 'running'].includes(job.status)) throw error('未知任务状态，请刷新后检查。', 'INVALID_JOB');
    await sleep(delay);
  }
  throw error('处理尚未结束，请点击继续查询；不会再次提交。', 'POLL_TIMEOUT', true);
}
module.exports = {base, configure, restore, loggedIn, logout, requireLogin, login, request, create, query, merge, poll, uuid, pendingJob, rememberJob};
