// Static checks only: this is NOT the WeChat compiler or device acceptance.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
function walk(dir) { return fs.readdirSync(dir, {withFileTypes: true}).flatMap(e => e.name.startsWith('.') || e.name === 'node_modules' ? [] : e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]); }
for (const file of walk(root)) {
  if (file.endsWith('.json')) JSON.parse(fs.readFileSync(file, 'utf8'));
  if (file.endsWith('.js')) execFileSync(process.execPath, ['--check', file]);
}
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json')));
for (const page of app.pages) {
  for (const ext of ['js', 'json', 'wxml']) assert.ok(fs.existsSync(path.join(root, page + '.' + ext)), page + '.' + ext);
  let spec; global.Page = p => { spec = p; };
  require(path.join(root, page + '.js'));
  const markup = fs.readFileSync(path.join(root, page + '.wxml'), 'utf8');
  for (const match of markup.matchAll(/(?:bind\w+|catch\w+)="([a-zA-Z]\w*)"/g)) assert.equal(typeof spec[match[1]], 'function', page + ': missing handler ' + match[1]);
  const stack = [];
  for (const match of markup.matchAll(/<\/?([a-z][a-z0-9-]*)\b[^>]*>/g)) {
    if (match[0].startsWith('</')) assert.equal(stack.pop(), match[1], page + ': mismatched tag');
    else if (!match[0].endsWith('/>')) stack.push(match[1]);
  }
  assert.equal(stack.length, 0, page + ': unclosed tags');
}
const config = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json')));
assert.equal(config.setting.urlCheck, true);
assert.ok(!config.appid, 'Real AppID belongs in ignored local config');
console.log('PASS: JS syntax, JSON, page registration, markup tags, event handlers and secure defaults. Not a WeChat build.');
