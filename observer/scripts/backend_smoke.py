"""Explicit isolated integration: real Java/Postgres + synthetic text. Never capture.

Run from observer/: uv run python scripts/backend_smoke.py --jar /path/to/backend.jar
Creates only a disposable Docker container and temporary loopback Java instance.
No external model/Wiki keys, worker, mock mode, Pensieve, or macOS notification.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import uuid
from datetime import datetime, timezone, timedelta

from decision_observer.core import Http, ObserverError, Runner, Store, observation


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def check_rejected(api, body, code):
    result = api.request('POST', '/observations/batch', body)
    assert result['rejected'][0]['code'] == code, 'Unexpected rejection category'


def smoke(base, code, home, seed):
    public = Http(base)
    assert public.request('GET', '/health')['status'] == 'ok'
    owner = Http(base, public.request('POST', '/auth/login', {'accessCode': code})['accessToken'])
    problem = owner.request('POST', '/problems', {'title': '合成观察联调', 'goal': '仅虚构数据验证接口'})['id']
    paired = owner.request('POST', '/devices', {'name': 'synthetic-observer-integration'})
    device, token = paired['device']['id'], paired['deviceToken']
    assert paired['device']['desiredState'] == 'paused'
    api = Http(base, token)
    config = {'backendUrl': base, 'deviceId': device, 'deviceToken': token,
              'pensieveUrl': 'http://127.0.0.1:8839', 'pensieveTimezone': 'Asia/Shanghai',
              'libraryId': 1, 'folderId': 1}
    store = Store(home)
    runner = Runner(config, store, problem, api=api, source=object(), notifier=lambda *a: None)
    now = datetime.now(timezone.utc)
    def item(ident, text, app="SyntheticApp"):
        return observation({'id': ident, 'file_created_at': now.isoformat(),
            'metadata_entries': [{'key': 'ocr_result', 'value': json.dumps([{'rec_txt': text}])}, {'key': 'active_app', 'value': app}]},
            config, problem, now, now)
    sample = item(1, '合成页面，没有真实个人信息。')
    body = {'deviceId': device, 'items': [sample]}
    try:
        check_rejected(api, body, 'DEVICE_PAUSED')
        try:
            api.request('GET', '/sources?problemId=' + problem)
            raise AssertionError('Device could read owner sources')
        except ObserverError as e:
            assert str(e) == 'HTTP 403'
        owner.request('PATCH', '/devices/' + device, {'desiredState': 'running'})
        runner.consent = True
        runner.heartbeat()
        store.enqueue(sample)
        runner.flush()
        assert store.pending(problem) is None
        assert sample['externalId'] in api.request('POST', '/observations/batch', body)['duplicates']
        sources = owner.request('GET', '/sources?problemId=' + problem)['items']
        assert sources[0]['locator']['frameId'] == 'pensieve:entity:1'
        assert sources[0]['locator']['resourceId'] == 'pensieve:library:1'
        bad = {**sample, 'externalId': 'bad-hash', 'contentHash': '0'*64}
        check_rejected(api, {'deviceId': device, 'items': [bad]}, 'HASH_MISMATCH')
        # Explicit HTTP-200 rejection is cached by A. Verify C rotates only known-failed keys.
        pending = item(2, '合成暂停竞争测试')
        store.enqueue(pending)
        before = store.pending(problem)[2]
        original = api.request
        paused = False
        def pause_before_upload(method, path, data=None, key=None):
            nonlocal paused
            if path == '/observations/batch' and not paused:
                owner.request('PATCH', '/devices/' + device, {'desiredState': 'paused'})
                paused = True
            return original(method, path, data, key)
        api.request = pause_before_upload
        try:
            runner.flush()
            raise AssertionError('Pause race was not rejected')
        except ObserverError:
            assert store.pending(problem)[2] != before
        api.request = original
        runner.consent = False  # Simulate the explicit new local run after pause.
        owner.request('PATCH', '/devices/' + device, {'desiredState': 'running'})
        runner.consent = True
        runner.flush()
        assert store.pending(problem) is None
        for i in range(20):
            store.enqueue(item(100+i, '长'*19990 + str(i)))
        batches = 0
        while store.pending(problem):
            runner.flush()
            batches += 1
        assert batches > 1
        store.enqueue(item(200, '中'+'😀'*12000, '😀'*150))
        runner.flush()
        assert store.pending(problem) is None
        runner.deliver()  # Empty query before explicit synthetic fixtures.
        failed_id, snooze_discussion = seed(problem, device)
        notified = []
        def synthetic_notifier(title, reason):
            notified.append(title)
            if title == 'synthetic-failure':
                raise ObserverError('Synthetic notification failure')
        runner.notifier = synthetic_notifier
        runner.deliver()
        assert runner.delivery_cursor is not None
        runner.deliver()
        assert runner.delivery_cursor is None
        runner.deliver()  # Backoff skips first failure; it must not starve later pages.
        assert len(notified) == 21
        pending_deliveries = api.request('GET', '/devices/' + device + '/deliveries?limit=30')['items']
        assert len(pending_deliveries) == 1
        assert pending_deliveries[0]['delivery']['id'] == failed_id
        assert pending_deliveries[0]['delivery']['status'] == 'failed'
        owner.request('PATCH', '/discussions/' + snooze_discussion, {'status': 'snoozed',
            'snoozeUntil': (datetime.now(timezone.utc)+timedelta(seconds=2)).isoformat()})
        runner.deliver()
        assert len(notified) == 21
        time.sleep(2.1)
        runner.deliver()
        assert len(notified) == 22, 'Snoozed delivery was not displayed again'
        assert len(api.request('GET', '/devices/' + device + '/deliveries?limit=30')['items']) == 1
        runner.deliver()
        assert len(notified) == 22, 'Same snooze generation displayed twice'
        other = owner.request('POST', '/devices', {'name': 'synthetic-other-device'})
        try:
            Http(base, other['deviceToken']).request('POST', '/deliveries/' + failed_id + '/ack', {'status': 'sent'})
            raise AssertionError('Cross-device ack unexpectedly allowed')
        except ObserverError as e:
            assert str(e) == 'HTTP 403'
        owner.request('PATCH', '/devices/' + device, {'desiredState': 'paused'})
        check_rejected(api, body, 'DEVICE_PAUSED')
        print(json.dumps({'backend': 'real-java-postgresql', 'data': 'synthetic',
            'checks': ['paused-rejection','device-owner-scope-denied','heartbeat','upload',
                'external-id-dedup','locator-roundtrip','bad-hash-rejection',
                'pause-race-known-rejection-resume','large-chinese-batch-splitting',
                'emoji-utf16-limits','snooze-redelivery-new-generation','delivery-pagination-21-fixtures','failed-notification-backoff','cross-device-ack-denied','final-paused'],
            'largeTextBatches': batches, 'capture': 'not-started',
            'modelWikiSystemNotification': 'not-executed', 'notificationAdapter': 'synthetic-notifier'}, ensure_ascii=False))
    finally:
        runner.consent = False
        owner.request('PATCH', '/devices/' + device, {'desiredState': 'paused'})
        api.request('POST', '/devices/' + device + '/heartbeat', {'reportedState': 'paused'})
        store.db.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file(): raise SystemExit('Backend jar missing')
    java_home = run('/usr/libexec/java_home', '-v', '17')
    name = 'decision-observer-smoke-' + uuid.uuid4().hex[:12]
    env = {k:v for k,v in os.environ.items() if not k.startswith(('APP_', 'DATABASE_', 'DEEPSEEK_', 'WIKI_'))}
    env.update(POSTGRES_PASSWORD=secrets.token_urlsafe(24), APP_OWNER_ACCESS_CODE=secrets.token_urlsafe(24),
        APP_TOKEN_SECRET=secrets.token_urlsafe(48), APP_WORKER_ENABLED='false', APP_MOCK_MODE='false',
        DEEPSEEK_API_KEY='', WIKI_ACCESS_TOKEN='', DATABASE_USER='decision_test')
    process = None
    created = False
    with tempfile.TemporaryDirectory(prefix='decision-observer-smoke-') as tmp:
        try:
            run('docker','run','--rm','-d','--name',name,'-e','POSTGRES_PASSWORD',
                '-e','POSTGRES_USER=decision_test','-e','POSTGRES_DB=decision_backend_test',
                '-p','127.0.0.1::5432','postgres:16',env=env)
            created = True
            port = run('docker','port',name,'5432/tcp').rsplit(':',1)[1]
            for _ in range(30):
                result = subprocess.run(['docker','exec',name,'pg_isready','-U','decision_test','-d','decision_backend_test'],capture_output=True)
                if result.returncode == 0: break
                time.sleep(1)
            else: raise RuntimeError('Temporary PostgreSQL not ready')
            with socket.socket() as sock:
                sock.bind(('127.0.0.1',0)); api_port=sock.getsockname()[1]
            env.update(APP_PORT=str(api_port), DATABASE_PASSWORD=env['POSTGRES_PASSWORD'],
                DATABASE_URL=f'jdbc:postgresql://127.0.0.1:{port}/decision_backend_test')
            with open(Path(tmp)/'backend.log','w') as log:
                process = subprocess.Popen([java_home+'/bin/java','-jar',str(jar),'--server.address=127.0.0.1'],env=env,cwd=tmp,stdout=log,stderr=log)
                base=f'http://127.0.0.1:{api_port}/api/v1'
                for _ in range(60):
                    if process.poll() is not None: raise RuntimeError('Temporary backend exited during startup')
                    try:
                        Http(base).request('GET','/health'); break
                    except ObserverError: time.sleep(1)
                else: raise RuntimeError('Temporary backend not ready')
                def seed(problem, device):
                    # Only this disposable test DB. Fixture insertion is not model generation evidence.
                    records = []
                    first = None
                    snooze_discussion = None
                    for i in range(21):
                        timestamp = (datetime.now(timezone.utc) + timedelta(seconds=i)).isoformat()
                        discussion, delivery = str(uuid.uuid4()), str(uuid.uuid4())
                        if first is None: first = delivery
                        if i == 1: snooze_discussion = discussion
                        records.extend([
                            ('discussion', {'id': discussion, 'problemId': problem,
                                'title': 'synthetic-failure' if i == 0 else 'synthetic-success-' + str(i),
                                'reason': '合成通知测试', 'evidence': [], 'status': 'unread',
                                'snoozeUntil': None, 'createdAt': timestamp, 'updatedAt': timestamp}),
                            ('delivery', {'id': delivery, 'discussionId': discussion, 'deviceId': device,
                                'channel': 'desktop', 'status': 'pending', 'error': None,
                                'createdAt': timestamp, 'updatedAt': timestamp})])
                    sql = '\n'.join("INSERT INTO records(id,kind,problem_id,data) VALUES ('" + data['id'] +
                        "','" + kind + "','" + problem + "','" + json.dumps(data).replace("'", "''") + "'::jsonb);"
                        for kind, data in records)
                    run('docker','exec','-i',name,'psql','-v','ON_ERROR_STOP=1','-U','decision_test',
                        '-d','decision_backend_test', input=sql)
                    return first, snooze_discussion
                smoke(base,env['APP_OWNER_ACCESS_CODE'],Path(tmp)/'observer',seed)
        finally:
            if process and process.poll() is None:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
            if created: run('docker','rm','-f',name)
            print('Temporary backend and PostgreSQL cleaned up; no capture started.')

if __name__ == '__main__': main()
