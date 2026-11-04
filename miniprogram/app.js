const api = require('./lib/api');
App({ onLaunch() { api.restore(); } });
