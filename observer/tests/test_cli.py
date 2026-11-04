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
