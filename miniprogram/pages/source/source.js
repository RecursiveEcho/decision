const {api, message, evidence} = require('../../lib/page');
Page({
  data: {item: null, error: '', loading: false},
  onLoad(options) { this.id = options.id; },
  onShow() { if (api.requireLogin()) this.load(); },
  async load() {
    if (this.data.loading) return;
    this.setData({loading: true, error: '', item: null});
    try { this.setData({item: evidence([await api.request('/sources/' + encodeURIComponent(this.id))])[0]}); }
    catch (err) { this.setData({error: message(err)}); }
    finally { this.setData({loading: false}); }
  },
  copy() {
    if (this.data.item && this.data.item.originUrl) wx.setClipboardData({data: this.data.item.originUrl, fail: () => this.setData({error: '复制失败，可在来源文本中手动选择链接。'})});
  }
});
