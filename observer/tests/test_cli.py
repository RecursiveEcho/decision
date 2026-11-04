import fcntl
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

class StatusTests(unittest.TestCase):
    def test_stopped_process_is_paused_even_with_pause_request(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([sys.executable, '-m', 'decision_observer', '--home', tmp, 'pause'], check=True, capture_output=True)
            result = subprocess.run([sys.executable, '-m', 'decision_observer', '--home', tmp, 'status'], check=True, capture_output=True, text=True)
            state = json.loads(result.stdout)
            self.assertEqual(state['reportedState'], 'paused')
            self.assertFalse(state['localConsent'])
            self.assertEqual(state['captureState'], 'unverified')
    def test_live_locked_process_with_pause_request_is_pausing(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, 'pause.request').touch()
            with open(Path(tmp, 'run.lock'), 'w') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                result = subprocess.run([sys.executable, '-m', 'decision_observer', '--home', tmp, 'status'], check=True, capture_output=True, text=True)
                state = json.loads(result.stdout)
                self.assertEqual(state['reportedState'], 'pausing')
                self.assertFalse(state['localConsent'])

class NotificationConfigTests(unittest.TestCase):
    def test_notification_only_config_saves_only_three_fields(self):
        from unittest.mock import patch
        from decision_observer.__main__ import main, load_config
        from decision_observer.core import atomic_json, ObserverError
        import uuid
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            atomic_json(home / 'config.json', {'libraryId': 99, 'folderId': 99, 'deviceId': 'old'})
            with patch('sys.argv', ['observer', '--home', tmp, 'configure', '--notifications-only']), \
                    patch('builtins.input', side_effect=['http://127.0.0.1:8080/api/v1', str(uuid.uuid4())]), \
                    patch('getpass.getpass', return_value='synthetic-device-token'), \
                    patch('builtins.print'), patch('decision_observer.core.Pensieve') as pensieve:
                main()
                pensieve.assert_not_called()
            saved = load_config(home, observation_required=False)
            self.assertEqual(set(saved), {'backendUrl', 'deviceId', 'deviceToken'})
            self.assertEqual((home/'config.json').stat().st_mode & 0o777, 0o600)
            with self.assertRaisesRegex(ObserverError, 'full configure'):
                load_config(home)

    def test_notification_cli_never_constructs_pensieve(self):
        from unittest.mock import patch
        from decision_observer.__main__ import main
        from decision_observer.core import atomic_json
        import uuid
        with tempfile.TemporaryDirectory() as tmp:
            atomic_json(Path(tmp)/'config.json', {'backendUrl': 'http://127.0.0.1:8080/api/v1',
                'deviceId': str(uuid.uuid4()), 'deviceToken': 'synthetic-device-token'})
            with patch('sys.argv', ['observer', '--home', tmp, 'notifications', '--once']), \
                    patch('decision_observer.core.Pensieve') as pensieve, \
                    patch('decision_observer.core.Http.request', return_value={'items': [], 'nextCursor': None}) as request, \
                    patch('builtins.print'):
                main()
                pensieve.assert_not_called()
                self.assertEqual(request.call_count, 1)
                self.assertEqual(request.call_args.args[0], 'GET')
                self.assertIn('/deliveries?', request.call_args.args[1])

    def test_notification_only_run_fails_before_queue_or_http(self):
        from unittest.mock import patch
        from decision_observer.core import Runner, Store, ObserverError
        from test_observer import CONFIG, PROBLEM, entity
        import json
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp))
            try:
                with store.db:
                    store.db.execute('INSERT INTO queue VALUES (?,?,?,?)', ('synthetic-retained', PROBLEM, '{}', 'test-key'))
                config = {k: CONFIG[k] for k in ('backendUrl', 'deviceId', 'deviceToken')}
                with patch('decision_observer.core.Pensieve') as pensieve, patch('decision_observer.core.Http.request') as request:
                    runner = Runner(config, store, PROBLEM)
                    with self.assertRaisesRegex(ObserverError, 'full configure'):
                        runner.run()
                    request.assert_not_called()
                    pensieve.assert_not_called()
                    self.assertFalse(runner.consent)
                self.assertEqual(store.db.execute('SELECT count(*) FROM queue').fetchone()[0], 1)
            finally:
                store.db.close()

    def test_minimal_config_delivers_synthetic_notification_without_pensieve(self):
        from unittest.mock import patch
        from decision_observer.core import Runner, Store
        from test_observer import CONFIG, PROBLEM, Api
        import uuid
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp))
            try:
                api = Api()
                api.delivery = {'delivery': {'id': str(uuid.uuid4()), 'status': 'pending', 'updatedAt': '2026-11-04T10:00:00Z'},
                                'discussion': {'title': '合成通知', 'reason': '仅通知配置测试'}}
                calls = []
                config = {k: CONFIG[k] for k in ('backendUrl', 'deviceId', 'deviceToken')}
                with patch('decision_observer.core.Pensieve') as pensieve:
                    Runner(config, store, None, api=api, notifier=lambda *args: calls.append(args)).deliver()
                    pensieve.assert_not_called()
                self.assertEqual(len(calls), 1)
                self.assertEqual(api.calls[-1][2]['status'], 'sent')
            finally:
                store.db.close()
