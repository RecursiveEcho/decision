// Stage an ignored, self-contained project with the user's real AppID.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const appid = process.env.WECHAT_APP_ID || '';
if (!/^wx[0-9a-f]{16}$/i.test(appid)) {
  console.error('请设置本人真实 WECHAT_APP_ID（wx 开头的 AppID）；不会生成或冒用测试 AppID。');
  process.exit(1);
}
const target = path.join(root, '.local', 'wechat-project');
fs.mkdirSync(target, {recursive: true, mode: 0o700});
for (const name of ['app.js', 'app.json', 'app.wxss', 'sitemap.json', 'lib', 'pages']) {
  fs.cpSync(path.join(root, name), path.join(target, name), {recursive: true});
}
const config = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json')));
config.appid = appid;
fs.writeFileSync(path.join(target, 'project.config.json'), JSON.stringify(config, null, 2) + '\n', {mode: 0o600});
console.log('本地导入目录：' + target);
console.log('每次源码修改后重新执行本命令更新副本。尚未执行微信工具编译或真机验证。');
