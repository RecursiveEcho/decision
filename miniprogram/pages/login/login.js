const {api, message, input} = require('../../lib/page');
Page({
  data: {apiBase: '', accessCode: '', busy: false, error: ''}, input,
  onLoad() { this.setData({apiBase: api.base()}); if (api.loggedIn()) wx.reLaunch({url: '/pages/problems/problems'}); },
  async submit() {
    if (this.data.busy) return;
    this.setData({busy: true, error: ''});
    try {
      api.configure(this.data.apiBase);
      if (!this.data.accessCode.trim()) throw new Error('请输入本人访问码。');
      await api.login(this.data.accessCode);
      this.setData({accessCode: ''});
      wx.reLaunch({url: '/pages/problems/problems'});
    } catch (err) { this.setData({error: message(err), accessCode: ''}); }
    finally { this.setData({busy: false}); }
  }
});
