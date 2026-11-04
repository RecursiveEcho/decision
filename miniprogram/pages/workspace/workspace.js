const {api, message, input, source, evidence} = require('../../lib/page');
const routes = {chat: 'messages', sources: 'sources', discussions: 'discussions', decisions: 'decisions'};
Page({
  data: {problemId: '', tab: 'chat', items: [], nextCursor: null, loading: false, busy: false, error: '', notice: '',
    content: '', sourceTitle: '', sourceUrl: '', sourceText: '', choice: '', rationale: '', expectedOutcome: '',
    reviewId: '', outcome: '', reflection: '', jobId: '', jobKind: '', jobState: '', polling: false},
  input, source,
  onLoad(options) {
    this._active = true;
    this.setData({problemId: options.id || ''});
    const pending = api.pendingJob(options.id);
    if (pending) this.setData({jobId: pending.id, jobKind: pending.kind, notice: '有未完成查询的任务，请继续查询原任务。'});
  },
  onShow() { this._active = true; if (api.requireLogin()) this.load(false); },
  onHide() { this.cancelPoll(); }, onUnload() { this.cancelPoll(); },
  cancelPoll() { this._active = false; if (this._control) this._control.cancelled = true; },
  switchTab(event) {
    if (this.data.loading || this.data.busy || this.data.polling) return;
    this.setData({tab: event.currentTarget.dataset.tab, items: [], nextCursor: null, error: ''}); this.load(false);
  },
  async load(append) {
    if (this.data.loading || !this.data.problemId) return;
    const tab = this.data.tab;
    this.setData({loading: true, error: ''});
    try {
      const path = tab === 'chat' ? '/problems/' + encodeURIComponent(this.data.problemId) + '/messages' : '/' + routes[tab];
      const result = await api.request(path + api.query({problemId: tab === 'chat' ? undefined : this.data.problemId, limit: 30, cursor: append ? this.data.nextCursor : null}));
      if (this._active && tab === this.data.tab) this.setData({items: api.merge(append ? this.data.items : [], evidence(result.items)), nextCursor: result.nextCursor});
    } catch (err) { if (this._active) this.setData({error: message(err)}); }
    finally { this.setData({loading: false}); }
  },
  refresh() { this.load(false); }, more() { if (this.data.nextCursor) this.load(true); },
  async submitJob(kind) {
    if (this.data.busy || this.data.jobId) return;
    this.setData({busy: true, error: '', notice: ''});
    try {
      const d = this.data;
      let path, body;
      if (kind === 'chat') {
        if (!d.content.trim()) throw new Error('请先输入讨论内容。');
        path = '/problems/' + encodeURIComponent(d.problemId) + '/messages'; body = {content: d.content.trim()};
      } else {
        if (!d.sourceText.trim() && !d.sourceUrl.trim()) throw new Error('链接与正文至少填写一项。');
        if (d.sourceUrl.trim() && !/^https?:\/\//i.test(d.sourceUrl.trim())) throw new Error('文章链接须以 https:// 或 http:// 开头。');
        path = '/sources'; body = {problemId: d.problemId, kind: 'article'};
        if (d.sourceTitle.trim()) body.title = d.sourceTitle.trim();
        if (d.sourceText.trim()) body.text = d.sourceText.trim();
        if (d.sourceUrl.trim()) body.url = d.sourceUrl.trim();
      }
      const job = await api.create(path, body);
      if (!job.id) throw new Error('服务端未返回任务编号，请刷新核对是否已保存。');
      api.rememberJob(d.problemId, {id: job.id, kind});
      this.setData({jobId: job.id, jobKind: kind, jobState: job.status, notice: job.mode === 'mock' ? '测试替身任务：不算真实服务验证。' : '已提交，正在处理。'});
      if (kind === 'chat') this.setData({content: ''});
      else this.setData({sourceTitle: '', sourceUrl: '', sourceText: ''});
    } catch (err) { this.setData({error: message(err)}); }
    finally { this.setData({busy: false}); }
    if (this._active && this.data.jobId) await this.resumeJob();
  },
  send() { return this.submitJob('chat'); }, ingest() { return this.submitJob('source'); },
  async resumeJob() {
    if (this.data.polling || !this.data.jobId) return;
    this._control = {cancelled: false};
    this.setData({polling: true, error: ''});
    try {
      const job = await api.poll(this.data.jobId, this._control, (item, warning) => {
        if (this._active) this.setData({jobState: item ? item.status : '网络中断，正在重试查询', notice: warning || (item.mode === 'mock' ? '测试替身任务：不算真实服务验证。' : '正在查询原任务。')});
      });
      api.rememberJob(this.data.problemId, null);
      this.setData({jobId: '', jobState: '', notice: job.mode === 'mock' ? '已完成测试替身任务，非真实模型结果。' : '处理完成。刷新列表可查看记录；聊天按时间升序，较新内容可能在后续页。'});
      await this.load(false);
    } catch (err) {
      if (err.code === 'JOB_FAILED' || err.statusCode === 404) { api.rememberJob(this.data.problemId, null); this.setData({jobId: '', jobState: ''}); }
      if (err.code !== 'CANCELLED' && this._active) this.setData({error: err.statusCode === 404 ? '任务已不存在。已解除提交限制，请先刷新记录核对是否已保存，再决定是否重新提交。' : message(err)});
    } finally { this.setData({polling: false}); }
  },
  async decide() {
    if (this.data.busy) return;
    const {choice, rationale, expectedOutcome} = this.data;
    if (![choice, rationale, expectedOutcome].every(x => x.trim())) { this.setData({error: '请填写决定、理由与预期结果。'}); return; }
    this.setData({busy: true, error: ''});
    try {
      const confirmed = await new Promise(resolve => wx.showModal({title: '确认记录决定', content: '这是你本人确认的决定，将保留当时理由。后续可以追加复盘。', success: r => resolve(r.confirm), fail: () => resolve(false)}));
      if (!confirmed) return;
      await api.create('/decisions', {problemId: this.data.problemId, choice: choice.trim(), rationale: rationale.trim(), expectedOutcome: expectedOutcome.trim()});
      this.setData({choice: '', rationale: '', expectedOutcome: '', notice: '决定已记录。'}); await this.load(false);
    } catch (err) { this.setData({error: message(err)}); }
    finally { this.setData({busy: false}); }
  },
  selectReview(event) { this.setData({reviewId: event.currentTarget.dataset.id, outcome: '', reflection: ''}); },
  cancelReview() { if (!this.data.busy) this.setData({reviewId: ''}); },
  async review() {
    if (this.data.busy || !this.data.reviewId) return;
    this.setData({busy: true, error: ''});
    try {
      if (!this.data.outcome.trim() || !this.data.reflection.trim()) throw new Error('请填写实际结果与复盘。');
      await api.create('/decisions/' + encodeURIComponent(this.data.reviewId) + '/reviews', {outcome: this.data.outcome.trim(), reflection: this.data.reflection.trim()});
      this.setData({reviewId: '', outcome: '', reflection: '', notice: '复盘已追加，原决定保留。'}); await this.load(false);
    } catch (err) { this.setData({error: message(err)}); }
    finally { this.setData({busy: false}); }
  }
});
