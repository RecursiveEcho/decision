const {api, message, input, evidence} = require('../../lib/page');
Page({
  data: {items: [], nextCursor: null, title: '', goal: '', constraints: '', unknowns: '', busy: false, loading: false, error: ''}, input,
  onShow() { if (api.requireLogin()) this.load(false); },
  async load(append) {
    if (this.data.loading) return;
    this.setData({loading: true, error: ''});
    try {
      const result = await api.request('/problems' + api.query({limit: 30, cursor: append ? this.data.nextCursor : null}));
      this.setData({items: api.merge(append ? this.data.items : [], evidence(result.items)), nextCursor: result.nextCursor});
    } catch (err) { this.setData({error: message(err)}); }
    finally { this.setData({loading: false}); }
  },
  more() { if (this.data.nextCursor) this.load(true); }, refresh() { this.load(false); },
  open(event) { wx.navigateTo({url: '/pages/workspace/workspace?id=' + encodeURIComponent(event.currentTarget.dataset.id)}); },
  async create() {
    if (this.data.busy) return;
    this.setData({busy: true, error: ''});
    try {
      const {title, goal, constraints, unknowns} = this.data;
      if (!title.trim() || !goal.trim()) throw new Error('请填写问题和目标。');
      const lines = value => value.split('\n').map(x => x.trim()).filter(Boolean);
      const result = await api.create('/problems', {title: title.trim(), goal: goal.trim(), constraints: lines(constraints), unknowns: lines(unknowns)});
      this.setData({title: '', goal: '', constraints: '', unknowns: ''});
      this.open({currentTarget: {dataset: {id: result.id}}});
    } catch (err) { this.setData({error: message(err)}); }
    finally { this.setData({busy: false}); }
  }, logout() { api.logout(); }
});
