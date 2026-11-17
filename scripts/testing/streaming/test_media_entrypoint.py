from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import fixture

sys.path.insert(0, str(fixture.REPO / 'media-server/hooks'))
import media_entrypoint


class MediaEntrypointTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.ready = Path(self.directory.name) / 'ready'
        self.stopped = threading.Event()
        self.requests = []
        self.control_ready = False
        self.callback_status = 200
        self.callback_attempts = 0
        self.restored = threading.Event()
        owner = self

        class Receiver(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def respond(self, status, body):
                payload = json.dumps(body).encode()
                self.send_response(status)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self):
                owner.requests.append(('GET', self.path))
                self.respond(200 if owner.control_ready else 503, {'started': 'boot-1'})

            def do_POST(self):
                owner.requests.append(('POST', self.path))
                owner.callback_attempts += 1
                self.respond(owner.callback_status, {'code': 1000, 'result': {'restoredChannels': 2}})
                owner.restored.set()

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Receiver)
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.environment = {'MEDIA_CONTROL_BASE_URL': base,
                            'STREAM_MEDIA_STARTUP_CALLBACK_URL': base + '/restore'}
        self.worker = None
        self.delay = patch.object(media_entrypoint, 'RETRY_DELAY', 0.02)
        self.delay.start()

    def tearDown(self):
        self.stopped.set()
        if self.worker:
            self.worker.join(timeout=3)
        self.delay.stop()
        self.server.shutdown()
        self.server.server_close()
        self.server_thread.join()
        self.directory.cleanup()

    def start(self):
        self.worker = threading.Thread(target=media_entrypoint.restore_ingest_paths,
                                       args=(self.environment, self.stopped, self.ready))
        self.worker.start()

    def wait_for(self, condition):
        deadline = time.monotonic() + 3
        while not condition() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(condition())

    def test_waits_for_control_api_then_calls_backend_once(self):
        self.start()
        self.wait_for(lambda: len(self.requests) >= 2)
        self.assertEqual(0, self.callback_attempts)
        self.assertFalse(self.ready.exists())
        self.control_ready = True
        self.worker.join(timeout=3)
        self.assertFalse(self.worker.is_alive())
        self.assertEqual(1, self.callback_attempts)
        self.assertTrue(self.ready.exists())
        self.assertEqual(('POST', '/restore'), self.requests[-1])

    def test_backend_can_start_later_and_failed_recovery_is_not_ready(self):
        self.control_ready = True
        self.callback_status = 503
        self.start()
        self.wait_for(lambda: self.callback_attempts >= 2)
        self.assertFalse(self.ready.exists())
        self.callback_status = 200
        self.worker.join(timeout=3)
        self.assertFalse(self.worker.is_alive())
        self.assertTrue(self.ready.exists())

    def test_shutdown_cancels_startup_retries(self):
        self.control_ready = True
        self.callback_status = 503
        self.start()
        self.assertTrue(self.restored.wait(timeout=3))
        self.stopped.set()
        self.worker.join(timeout=3)
        self.assertFalse(self.worker.is_alive())
        self.assertFalse(self.ready.exists())

    def test_fixture_explicitly_disables_recovery(self):
        self.control_ready = True
        self.environment['STREAM_MEDIA_STARTUP_CALLBACK_URL'] = ''
        self.start()
        self.worker.join(timeout=3)
        self.assertEqual(0, self.callback_attempts)
        self.assertTrue(self.ready.exists())

    def test_supervisor_forwards_sigterm_and_cleans_up_readiness(self):
        self.control_ready = True
        child_ready = Path(self.directory.name) / 'child-ready'
        child_code = ('import signal, time; from pathlib import Path; '
                      'signal.signal(signal.SIGINT, lambda *_: exit(0)); '
                      f'Path({str(child_ready)!r}).touch(); time.sleep(30)')
        process = subprocess.Popen(
            [sys.executable, str(fixture.REPO / 'media-server/hooks/media_entrypoint.py'),
             sys.executable, '-c', child_code],
            env=dict(self.environment), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertTrue(self.restored.wait(timeout=5))
            self.wait_for(media_entrypoint.READY_FILE.exists)
            self.wait_for(child_ready.exists)
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=5)
            self.assertEqual(0, process.returncode, (stdout, stderr))
            self.assertFalse(media_entrypoint.READY_FILE.exists())
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()


if __name__ == '__main__':
    unittest.main()
