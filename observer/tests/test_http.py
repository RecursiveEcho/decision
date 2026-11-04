"""Real local HTTP transport against synthetic servers, not a live backend/Pensieve."""
import json
import tempfile
import threading
import unittest
import uuid
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from decision_observer.core import Http, ObserverError, Runner, Store
from test_observer import CONFIG, PROBLEM, entity

class TransportTests(unittest.TestCase):
    def setUp(self):
        self.requests=[]
        requests=self.requests
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def reply(self,body,status=200):
                data=json.dumps(body).encode(); self.send_response(status)
                self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
            def do_GET(self):
                requests.append((self.path,dict(self.headers),None))
                if self.path=='/redirect':
                    self.send_response(302); self.send_header('Location','http://example.com/'); self.end_headers(); return
                if '/folders/1/entities?' in self.path: return self.reply([entity()])
                return self.reply({'error':'synthetic secret must not escape'},401)
            def do_POST(self):
                body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append((self.path,dict(self.headers),body))
                if self.path.endswith('/heartbeat'): return self.reply({'device':{'desiredState':'running'}})
                if self.path.endswith('/observations/batch'): return self.reply({'accepted':[body['items'][0]['externalId']]})
                return self.reply({},404)
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        self.base=f'http://127.0.0.1:{server.server_port}'
    def test_protocol_transport(self):
        with tempfile.TemporaryDirectory() as tmp:
            config={**CONFIG,'backendUrl':self.base+'/api/v1','pensieveUrl':self.base}
            store=Store(Path(tmp))
            try:
                runner=Runner(config,store,PROBLEM); runner.consent=True; runner.since-=timedelta(seconds=1); runner.tick()
                posts=[x for x in self.requests if x[0].endswith('/observations/batch')]
                self.assertEqual(len(posts),1)
                self.assertEqual(posts[0][1]['Authorization'],'Bearer synthetic-token')
                uuid.UUID(posts[0][1]['Idempotency-Key'])
                local=[x for x in self.requests if '/folders/' in x[0]][0]
                self.assertNotIn('Authorization',local[1])
                self.assertIsNone(store.pending(PROBLEM))
            finally: store.db.close()
    def test_redirect_refused(self):
        with self.assertRaisesRegex(ObserverError,'Redirect refused'): Http(self.base).request('GET','/redirect')
    def test_response_body_not_exposed(self):
        with self.assertRaisesRegex(ObserverError,'^HTTP 401$'): Http(self.base).request('GET','/private')
