const api = require('./api');
function message(err) { return err && err.message ? err.message : '操作失败，请稍后重试。'; }
function input(event) { this.setData({[event.currentTarget.dataset.field]: event.detail.value}); }
function source(event) { wx.navigateTo({url: '/pages/source/source?id=' + encodeURIComponent(event.currentTarget.dataset.id)}); }
function time(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = n => String(n).padStart(2, '0');
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
}
function evidence(items) {
  return (items || []).map(item => {
    const result = Object.assign({}, item, {
      modeLabel: item.mode === 'mock' ? '测试替身 · 非真实模型' : '',
      evidence: (item.evidence || []).map(e => Object.assign({}, e, {relationLabel: {supports: '支持', contradicts: '相反证据', context: '背景'}[e.relation] || e.relation}))
    });
    for (const key of ['createdAt', 'updatedAt', 'capturedAt', 'confirmedAt', 'recordedAt', 'snoozeUntil']) if (result[key]) result[key] = time(result[key]);
    if (item.reviews) result.reviews = evidence(item.reviews);
    return result;
  });
}
module.exports = {api, message, input, source, evidence, time};
