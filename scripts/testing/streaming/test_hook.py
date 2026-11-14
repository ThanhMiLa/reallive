from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import signal
import subprocess
import threading
import unittest
from uuid import uuid4

import fixture


class HookTest(unittest.TestCase):
    def setUp(self):
        self.bodies = []
        self.callback_tokens = []
        self.received = threading.Event()
        self.status_code = 200
        owner = self

        class Receiver(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                owner.bodies.append(body)
                owner.callback_tokens.append(self.headers.get('X-Internal-Token'))
                self.send_response(owner.status_code)
                self.send_header('Content-Length', '0')
                self.end_headers()
                owner.received.set()

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Receiver)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.environment = dict(os.environ,
                                STREAM_MEDIA_CALLBACK_URL='http://127.0.0.1:' + str(self.server.server_port),
                                MTX_PATH='ingest/' + str(uuid4()), MTX_SOURCE_ID=str(uuid4()),
                                MTX_SOURCE_TYPE='rtmpConn', MTX_QUERY='token=must-never-be-sent')
        self.process = None

    def tearDown(self):
        if self.process and self.process.poll() is None:
            self.process.kill()
            self.process.communicate()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def launch(self, env=None, action='online'):
        import sys
        run_env = env if env is not None else self.environment
        self.process = subprocess.Popen([sys.executable, str(fixture.REPO / 'media-server/hooks/media_hook.py'), action],
                                        env=run_env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_single_shot_success(self):
        self.status_code = 200
        self.launch()
        stdout, stderr = self.process.communicate(timeout=5)
        self.assertEqual(0, self.process.returncode)
        self.assertEqual(1, len(self.bodies))
        event = json.loads(self.bodies[0])
        self.assertEqual('ONLINE', event['eventType'])
        self.assertEqual(self.environment['MTX_SOURCE_ID'], event['sourceId'])
        self.assertEqual(self.environment['MTX_PATH'], event['path'])
        self.assertNotIn(b'must-never-be-sent', b''.join(self.bodies) + stdout + stderr)
        self.assertNotIn('MTX_QUERY', event)

    def test_transient_failure_retries_three_times_with_identical_event_id(self):
        self.status_code = 503
        self.launch()
        stdout, stderr = self.process.communicate(timeout=5)
        self.assertEqual(1, self.process.returncode)
        self.assertEqual(3, len(self.bodies))
        events = [json.loads(body) for body in self.bodies]
        self.assertEqual(1, len({event['eventId'] for event in events}))
        self.assertIn(b'Media callback unavailable; eventId=', stderr)

    def test_single_shot_client_error(self):
        self.status_code = 400
        self.launch()
        stdout, stderr = self.process.communicate(timeout=5)
        self.assertEqual(1, self.process.returncode)
        self.assertEqual(1, len(self.bodies))
        self.assertIn(b'Media callback rejected; eventId=', stderr)

    def test_invalid_path_fails_without_request(self):
        for bad_path in ['invalid/' + str(uuid4()), 'live/' + str(uuid4())]:
            with self.subTest(path=bad_path):
                bad_env = dict(self.environment, MTX_PATH=bad_path)
                self.launch(env=bad_env)
                stdout, stderr = self.process.communicate(timeout=5)
                self.assertEqual(1, self.process.returncode)
                self.assertEqual(0, len(self.bodies))
                self.assertIn(b'Media hook failed; reason=invalid-ingest-path', stderr)

    def test_internal_callback_needs_no_credentials(self):
        self.launch()
        self.process.communicate(timeout=5)
        self.assertEqual(0, self.process.returncode)
        self.assertEqual([None], self.callback_tokens)


if __name__ == '__main__':
    unittest.main()
