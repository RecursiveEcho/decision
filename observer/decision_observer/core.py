from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, quote
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from zoneinfo import ZoneInfo


class ObserverError(Exception):
    """Safe message: never include response bodies, tokens, paths or captured text."""


def utcnow():
    return datetime.now(timezone.utc).isoformat()


def valid_url(url, local_only=False):
    p = urlsplit(url)
    local = p.hostname in ('127.0.0.1', 'localhost', '::1')
    if p.username or p.password or p.query or p.fragment or not p.hostname:
        raise ObserverError('Invalid service URL')
    if local_only and not local:
        raise ObserverError('Pensieve must use a loopback URL')
    if p.scheme != 'https' and not (local and p.scheme == 'http'):
        raise ObserverError('Remote backend requires HTTPS')
    return url.rstrip('/')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ObserverError('Redirect refused')


class Http:
    def __init__(self, base, token=None, local_only=False):
        self.base = valid_url(base, local_only)
        self.token = token
        # Ignore machine HTTP proxies: local private history must stay local.
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def request(self, method, path, body=None, key=None):
        headers = {'Accept': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if body is not None:
            headers['Content-Type'] = 'application/json'
        if method == 'POST':
            headers['Idempotency-Key'] = key or str(uuid.uuid4())
        req = Request(self.base + path, method=method, headers=headers,
                      data=None if body is None else json.dumps(body).encode())
        try:
            with self.opener.open(req, timeout=2) as response:
                raw = response.read(8_000_001)
                if len(raw) > 8_000_000:
                    raise ObserverError('Response too large')
                return json.loads(raw)
        except HTTPError as e:
            raise ObserverError(f'HTTP {e.code}') from None
        except (URLError, TimeoutError, OSError, ValueError):
            raise ObserverError('Service unavailable or invalid JSON') from None


class Store:
    def __init__(self, home: Path):
        home.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(home, 0o700)
        self.home = home
        self.db = sqlite3.connect(home / 'queue.sqlite')
        os.chmod(home / 'queue.sqlite', 0o600)
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS queue (id TEXT PRIMARY KEY, problem TEXT NOT NULL,
            payload TEXT NOT NULL, request_key TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS seen (id TEXT PRIMARY KEY, recorded REAL NOT NULL);
          CREATE TABLE IF NOT EXISTS content_cursor (problem TEXT PRIMARY KEY, hash TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS notifications (id TEXT PRIMARY KEY, status TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS notification_generation (id TEXT PRIMARY KEY, generation TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS notification_retry (id TEXT PRIMARY KEY, attempts INTEGER NOT NULL, next_at REAL NOT NULL);
        ''')

    def save_status(self, state, error=None):
        atomic_json(self.home / 'status.json', {'reportedState': state,
            'localConsent': state == 'running', 'updatedAt': utcnow(),
            'lastError': error, 'captureState': 'unverified',
            'captureNotice': '同步已停，底层采集状态未确认' if state != 'running'
                             else '仅同步文字；底层采集由 Pensieve 独立控制'})

    def enqueue(self, item):
        ident = item['externalId']
        if self.db.execute('SELECT 1 FROM seen WHERE id=?', (ident,)).fetchone():
            return
        if self.db.execute('SELECT 1 FROM queue WHERE id=?', (ident,)).fetchone():
            return
        previous = self.db.execute('SELECT payload FROM queue WHERE problem=? ORDER BY rowid DESC LIMIT 1', (item['problemId'],)).fetchone()
        previous_hash = json.loads(previous[0])['contentHash'] if previous else None
        if previous_hash is None:
            row = self.db.execute('SELECT hash FROM content_cursor WHERE problem=?', (item['problemId'],)).fetchone()
            previous_hash = row[0] if row else None
        if previous_hash == item['contentHash']:
            self.accepted(ident)
            return
        if self.db.execute('SELECT COUNT(*) FROM queue').fetchone()[0] >= 50:
            raise ObserverError('Queue full; paused, explicit resume required')
        with self.db:
            self.db.execute('INSERT INTO queue VALUES (?,?,?,?)',
                (ident, item['problemId'], json.dumps(item), str(uuid.uuid4())))

    def pending(self, problem):
        row = self.db.execute('SELECT id,payload,request_key FROM queue WHERE problem=? ORDER BY rowid LIMIT 1', (problem,)).fetchone()
        return (row[0], json.loads(row[1]), row[2]) if row else None

    def accepted(self, ident):
        with self.db:
            row = self.db.execute('SELECT problem,payload FROM queue WHERE id=?', (ident,)).fetchone()
            if row:
                self.db.execute('INSERT OR REPLACE INTO content_cursor VALUES (?,?)', (row[0], json.loads(row[1])['contentHash']))
            self.db.execute('INSERT OR REPLACE INTO seen VALUES (?,?)', (ident, time.time()))
            self.db.execute('DELETE FROM queue WHERE id=?', (ident,))
            # Dedup metadata is bounded; text exists only in the 50-item queue.
            self.db.execute('DELETE FROM seen WHERE id IN (SELECT id FROM seen ORDER BY recorded DESC LIMIT -1 OFFSET 10000)')

    def clear_queue(self):
        with self.db:
            self.db.execute('DELETE FROM queue')


def atomic_json(path, value):
    tmp = path.with_suffix('.tmp-' + uuid.uuid4().hex)
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(value, f, ensure_ascii=False)
    os.replace(tmp, path)


def truncate_utf16(text, units):
    """Match Java length limits without splitting a Unicode code point."""
    return text.encode('utf-16-le')[:units * 2].decode('utf-16-le', errors='ignore')


def observation(entity, config, problem, since, now):
    # Pensieve returns naive local timestamps; the selected IANA zone is explicit.
    captured = datetime.fromisoformat(entity['file_created_at'].replace('Z', '+00:00'))
    if captured.tzinfo is None:
        captured = captured.replace(tzinfo=ZoneInfo(config['pensieveTimezone']))
    captured = captured.astimezone(timezone.utc)
    if not since <= captured <= now:
        return None
    meta = {m['key']: m['value'] for m in entity.get('metadata_entries', [])}
    raw = meta.get('ocr_result')
    if not raw:
        return None  # OCR may finish later; do not advance past it permanently.
    lines = json.loads(raw)
    if not isinstance(lines, list):
        return None
    text = '\n'.join(x['rec_txt'] for x in lines if isinstance(x, dict)
                     and isinstance(x.get('rec_txt'), str)).strip()
    if not text:
        return None
    text = truncate_utf16(text, 20000)
    entity_id = int(entity['id'])
    library_id = int(config['libraryId'])
    return {'externalId': f'pensieve:{library_id}:{entity_id}', 'problemId': problem,
            'text': text, 'appName': truncate_utf16(str(meta.get('active_app', 'unknown')), 200),
            'capturedAt': captured.isoformat(), 'extractionMethod': 'ocr',
            'sourceLocator': {'resourceId': f'pensieve:library:{library_id}', 'frameId': f'pensieve:entity:{entity_id}'},
            'contentHash': hashlib.sha256(text.encode()).hexdigest()}


class Pensieve:
    def __init__(self, config):
        self.config = config
        self.http = Http(config['pensieveUrl'], local_only=True)

    def read(self, since):
        c = self.config
        # Bounded recent window, revisited for delayed OCR. No full-archive scan.
        path = (f"/api/libraries/{c['libraryId']}/folders/{c['folderId']}/entities"
                '?limit=400&offset=0&order_by=file_created_at:desc')
        entities = self.http.request('GET', path)
        if not isinstance(entities, list):
            raise ObserverError('Unsupported Pensieve response')
        return entities


def notify(title, reason):
    if sys.platform != 'darwin':
        raise ObserverError('Desktop notifications require macOS')
    # Untrusted strings are argv, never interpolated into AppleScript source.
    script = 'on run argv\ndisplay notification (item 2 of argv) with title (item 1 of argv)\nend run'
    try:
        subprocess.run(['osascript', '-e', script, '--', title[:160], reason[:500]],
                       check=True, capture_output=True, timeout=2)
    except (OSError, subprocess.SubprocessError):
        raise ObserverError('macOS notification rejected') from None


class Runner:
    def __init__(self, config, store, problem, api=None, source=None, notifier=notify):
        self.config, self.store, self.problem = config, store, problem
        self.api = api or Http(config['backendUrl'], config['deviceToken'])
        self.source = source or Pensieve(config)
        self.notifier = notifier
        self.since = datetime.now(timezone.utc)
        self.consent = False
        self.lease = 0.0
        self.stop_file = store.home / 'pause.request'
        self.last_observation = None
        self.pending_ocr = set()
        self.delivery_cursor = None

    def allowed(self):
        return self.consent and not self.stop_file.exists() and time.monotonic() < self.lease

    def heartbeat(self):
        if self.stop_file.exists():
            raise ObserverError('Local pause requested')
        result = self.api.request('POST', f"/devices/{self.config['deviceId']}/heartbeat",
            {'reportedState': 'running' if self.consent else 'paused',
             'lastObservationAt': self.last_observation})
        if result['device']['desiredState'] != 'running':
            raise ObserverError('Server paused; explicit local run required')
        self.lease = time.monotonic() + 8

    def tick(self):
        self.heartbeat()
        if not self.allowed():
            raise ObserverError('Consent expired')
        # Drain previously consented data before accepting new observations.
        self.flush()
        if not self.allowed():
            raise ObserverError('Consent expired')
        entities = self.source.read(self.since)
        ids = {int(e['id']) for e in entities}
        if self.pending_ocr - ids:
            raise ObserverError('Delayed OCR fell out of recent window; paused')
        if len(entities) >= 400:
            oldest = entities[-1]
            stamp = datetime.fromisoformat(oldest['file_created_at'].replace('Z', '+00:00'))
            if stamp.tzinfo is None:
                stamp = stamp.replace(tzinfo=ZoneInfo(self.config['pensieveTimezone']))
            ident = f"pensieve:{self.config['libraryId']}:{int(oldest['id'])}"
            known = self.store.db.execute('SELECT 1 FROM seen WHERE id=?', (ident,)).fetchone()
            if stamp >= self.since and not known:
                raise ObserverError('Unread observations exceed recent window; paused')
        for entity in reversed(entities):
            if not self.allowed():
                raise ObserverError('Consent expired')
            item = observation(entity, self.config, self.problem, self.since, datetime.now(timezone.utc))
            ident = int(entity['id'])
            if item:
                self.pending_ocr.discard(ident)
                self.store.enqueue(item)
            else:
                stamp = datetime.fromisoformat(entity['file_created_at'].replace('Z', '+00:00'))
                if stamp.tzinfo is None:
                    stamp = stamp.replace(tzinfo=ZoneInfo(self.config['pensieveTimezone']))
                if stamp >= self.since:
                    meta = {m['key']: m['value'] for m in entity.get('metadata_entries', [])}
                    raw = meta.get('ocr_result')
                    if raw and isinstance(json.loads(raw), list):
                        self.pending_ocr.discard(ident)
                        self.store.accepted(f"pensieve:{self.config['libraryId']}:{ident}")
                    else:
                        self.pending_ocr.add(ident)
            if self.store.db.execute('SELECT COUNT(*) FROM queue').fetchone()[0] >= 50:
                break  # Continue from the recent window next tick after draining.
        self.flush()
        self.store.save_status('running')

    def flush(self):
        rows = self.store.db.execute('SELECT id,payload,request_key FROM queue WHERE problem=? ORDER BY rowid LIMIT 50', (self.problem,)).fetchall()
        if not rows:
            return
        self.heartbeat()
        if not self.allowed():
            raise ObserverError('Consent expired')
        selected, items = [], []
        for row in rows:
            candidate = items + [json.loads(row[1])]
            body = {'deviceId': self.config['deviceId'], 'items': candidate}
            if len(json.dumps(body).encode()) > 900_000:
                break
            selected.append(row)
            items = candidate
        if not selected:
            raise ObserverError('Single observation exceeds HTTP body budget')
        rows = selected
        key = rows[0][2] if len(rows) == 1 else str(uuid.uuid5(uuid.NAMESPACE_URL, '|'.join(row[2] for row in rows)))
        response = self.api.request('POST', '/observations/batch',
            {'deviceId': self.config['deviceId'], 'items': items}, key=key)
        accepted = set(response.get('accepted', []) + response.get('duplicates', []))
        for item in items:
            if item['externalId'] in accepted:
                self.store.accepted(item['externalId'])
                self.last_observation = item['capturedAt']
        rejected = {r['externalId'] for r in response.get('rejected', [])}
        # An explicit rejection is a known outcome; a new attempt needs a new key.
        # Unknown network outcomes keep the original key for safe replay.
        with self.store.db:
            for item in items:
                if item['externalId'] in rejected and item['externalId'] not in accepted:
                    self.store.db.execute('UPDATE queue SET request_key=? WHERE id=?',
                        (str(uuid.uuid4()), item['externalId']))
        if any(item['externalId'] not in accepted for item in items):
            raise ObserverError('Observation rejected; retained for explicit retry')

    def deliver(self):
        path = f"/devices/{self.config['deviceId']}/deliveries?limit=20"
        if self.delivery_cursor:
            path += "&cursor=" + quote(self.delivery_cursor, safe="")
        response = self.api.request('GET', path)
        for item in response['items']:
            delivery, discussion = item['delivery'], item['discussion']
            ident = str(uuid.UUID(delivery['id']))
            old_generation = self.store.db.execute('SELECT generation FROM notification_generation WHERE id=?', (ident,)).fetchone()
            incoming = delivery.get('updatedAt', 'legacy')
            generation = old_generation[0] if old_generation else incoming
            if old_generation is None or (delivery.get('status') == 'pending' and incoming != generation):
                generation = incoming
                with self.store.db:
                    self.store.db.execute('INSERT OR REPLACE INTO notification_generation VALUES (?,?)', (ident, generation))
                    self.store.db.execute('DELETE FROM notifications WHERE id=?', (ident,))
                    self.store.db.execute('DELETE FROM notification_retry WHERE id=?', (ident,))
            previous = self.store.db.execute('SELECT status FROM notifications WHERE id=?', (ident,)).fetchone()
            status = previous[0] if previous else None
            if status != 'sent':
                retry = self.store.db.execute('SELECT attempts,next_at FROM notification_retry WHERE id=?', (ident,)).fetchone()
                if retry and retry[1] > time.time():
                    continue
                try:
                    self.notifier(discussion['title'], discussion['reason'])
                    status = 'sent'
                except ObserverError:
                    status = 'failed'
                with self.store.db:
                    self.store.db.execute('INSERT OR REPLACE INTO notifications VALUES (?,?)', (ident, status))
                    if status == 'failed':
                        attempts = retry[0] + 1 if retry else 1
                        self.store.db.execute('INSERT OR REPLACE INTO notification_retry VALUES (?,?,?)',
                            (ident, attempts, time.time() + min(3600, 30 * 2 ** min(attempts - 1, 7))))
                    else:
                        self.store.db.execute('DELETE FROM notification_retry WHERE id=?', (ident,))
            self.api.request('POST', f'/deliveries/{ident}/ack',
                {'status': status, 'error': None if status == 'sent' else 'Desktop notification failed'},
                key=str(uuid.uuid5(uuid.NAMESPACE_URL, ident + status + generation)))
        self.delivery_cursor = response.get('nextCursor')

    def run(self, resume=False):
        if not resume:
            self.store.clear_queue()
        elif self.store.db.execute('SELECT 1 FROM queue WHERE problem<>?', (self.problem,)).fetchone():
            raise ObserverError('Queued observations belong to another problem')
        self.stop_file.unlink(missing_ok=True)
        failure = None
        try:
            self.heartbeat()  # No read before remote approval.
            self.consent = True
            self.store.save_status('running')
            while self.allowed():
                self.tick()
                # Short sleeps let another CLI invocation revoke consent promptly.
                for _ in range(20):
                    if self.stop_file.exists():
                        break
                    time.sleep(0.1)
        except KeyboardInterrupt:
            pass
        except Exception as e:
            # No raw exception string: third-party payloads may contain private data.
            message = str(e) if isinstance(e, ObserverError) else 'Adapter validation failed'
            failure = message
            raise ObserverError(message) from None
        finally:
            self.consent = False
            self.store.save_status('paused', failure)
            try:
                self.api.request('POST', f"/devices/{self.config['deviceId']}/heartbeat",
                                 {'reportedState': 'paused'})
            except Exception:
                pass
