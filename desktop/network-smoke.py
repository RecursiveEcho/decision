#!/usr/bin/env python3
"""Exercise upstream transport failure and explicit retry with synthetic local HTTP."""
import argparse
import base64
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import secrets
import socket
import tempfile
import threading
import time
from types import SimpleNamespace

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('native_smoke', HERE / 'native-smoke.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


def exercise(runtime, directory, stack):
    provider = {'mode': 'http_error', 'requests': 0}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            length = int(self.headers.get('Content-Length', '0'))
            native.require(0 < length < 1024 * 1024, 'Unexpected synthetic request size.')
            self.rfile.read(length)
            native.require(self.headers.get('Authorization') == 'Bearer synthetic-network-fixture',
                           'Unexpected provider credential.')
            provider['requests'] += 1
            if provider['mode'] == 'disconnect':
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            if provider['mode'] == 'http_error':
                self.send_response(503)
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            answer = {'content': '[TEST HTTP] Synthetic provider recovered.',
                      'evidence': [], 'uncertainties': ['Synthetic acceptance response.']}
            body = json.dumps({'choices': [{'message': {'content': json.dumps(answer)},
                                            'finish_reason': 'stop'}],
                               'usage': {'prompt_tokens': 10, 'completion_tokens': 10}}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    stack.callback(server.server_close)
    stack.callback(server.shutdown)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    sandbox = native.Sandbox(runtime, directory, stack)
    password, owner = secrets.token_hex(24), secrets.token_hex(24)
    sandbox.start_pg([('decision', 'decision_app', password),
                      ('decision_wiki', 'decision_wiki', secrets.token_hex(24))])
    client = sandbox.start_java(password, owner,
        SimpleNamespace(base=f'http://127.0.0.1:{sandbox.wiki}'), '', model_env={
            'APP_MOCK_MODE': 'false', 'DEEPSEEK_API_KEY': 'synthetic-network-fixture',
            'DEEPSEEK_BASE_URL': f'http://127.0.0.1:{server.server_port}',
            'DEEPSEEK_MODEL': 'synthetic-network-fixture',
        })
    client.export()  # Authenticate against this disposable installation only.
    text = 'Synthetic retained attachment during upstream failure.\n'
    for mode in ('http_error', 'disconnect'):
        provider['mode'] = mode
        content = f'Synthetic {mode} recovery acceptance'
        created = client.request('/conversations', {
            'content': content, 'thinking': 'off',
            'attachments': [{'name': 'network-fixture.txt', 'mimeType': 'text/plain',
                             'dataUrl': 'data:text/plain;base64,' + base64.b64encode(text.encode()).decode()}],
        })
        problem, job = created['problem']['id'], created['job']['id']
        native.wait_until('upstream failure', None,
                          lambda: client.request('/jobs/' + job)['status'] == 'failed')
        failed = client.request('/jobs/' + job)
        native.require(failed['error']['code'].startswith('UPSTREAM_'),
                       'Failure was not an upstream transport error.')
        attempts = provider['requests']
        time.sleep(1.2)
        native.require(provider['requests'] == attempts, 'The failed operation retried without consent.')
        messages = client.request(f'/problems/{problem}/messages')['items']
        native.require(len(messages) == 1 and messages[0]['attachments'][0]['status'] == 'ready',
                       'The saved user message or parsed attachment was lost.')
        native.require(messages[0]['content'] == content, 'The saved user message changed.')
        provider['mode'] = 'ok'
        time.sleep(1.2)
        native.require(provider['requests'] == attempts, 'Provider recovery replayed the operation.')
        retry = client.request(f'/jobs/{job}/retry', {})

        def recovered():
            result = client.request('/jobs/' + retry['id'])
            if result['status'] == 'failed':
                raise RuntimeError('Synthetic retry failed: ' + result['error']['code'])
            return result['status'] == 'succeeded'

        native.wait_until('explicit retry after provider recovery', None,
                          recovered)
        messages = client.request(f'/problems/{problem}/messages')['items']
        native.require(len(messages) == 2 and messages[-1]['role'] == 'assistant',
                       'Explicit retry did not produce exactly one assistant message.')
        print(f'PASS: {mode}, retained message/attachment, no automatic replay and explicit retry.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, default=native.ROOT / '.local/desktop/runtime')
    args = parser.parse_args()
    directory = Path(tempfile.mkdtemp(dir=native.ROOT / '.local/desktop', prefix='network-smoke-'))
    print(f'Disposable network acceptance: {directory}', flush=True)
    with ExitStack() as stack:
        exercise(args.runtime.resolve(strict=True), directory, stack)
    print('All disposable services and the synthetic provider stopped.', flush=True)


if __name__ == '__main__':
    main()
