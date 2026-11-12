// Keep the upstream Wiki.js files intact while restricting its desktop HTTP entry point.
const crypto = require('node:crypto');
const http = require('node:http');
const path = require('node:path');

const key = process.env.DECISION_WIKI_CHALLENGE_KEY || '';
const port = Number(process.env.DECISION_WIKI_PORT);
if (!/^[0-9a-f]{64}$/.test(key) || !Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('Missing desktop Wiki identity configuration');
}
const host = `127.0.0.1:${port}`;
const origin = `http://${host}`;
const emit = http.Server.prototype.emit;
http.Server.prototype.emit = function (event, ...args) {
  if (event !== 'request') return emit.call(this, event, ...args);
  const [request, response] = args;
  response.setHeader('X-Content-Type-Options', 'nosniff');
  response.setHeader('Referrer-Policy', 'no-referrer');
  const site = request.headers['sec-fetch-site'];
  if (request.headers.host !== host ||
      (request.headers.origin && request.headers.origin !== origin) ||
      site === 'cross-site' || site === 'same-site') {
    response.writeHead(403);
    response.end();
    return true;
  }
  if (request.url !== '/_desktop/ready') return emit.call(this, event, ...args);
  const challenge = request.headers['x-decision-challenge'];
  if (request.method !== 'GET' || typeof challenge !== 'string' || !/^[0-9a-f]{64}$/.test(challenge)) {
    response.writeHead(400);
    response.end();
    return true;
  }
  response.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
  response.end(crypto.createHmac('sha256', Buffer.from(key, 'hex')).update(challenge).digest('hex'));
  return true;
};
require(path.join(__dirname, 'wiki', 'server'));
