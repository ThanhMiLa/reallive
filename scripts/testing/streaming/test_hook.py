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
        self.received = threading.Event()
        self.status_code = 200
        owner = self

        class Receiver(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                owner.bodies.append(body)
                self.send_response(owner.status_code)
                self.send_header('Content-Length', '0')
                self.end_headers()
                owner.received.set()

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Receiver)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.environment = dict(os.environ,
                                STREAM_MEDIA_CALLBACK_URL='http://127.0.0.1:' + str(self.server.server_port),
                                MTX_PATH='live/' + str(uuid4()), MTX_SOURCE_ID=str(uuid4()),
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
        self.process = subprocess.Popen([sys.executable, str(fixture.REPO / 'mediamtx/media_hook.py'), action],
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

    def test_single_shot_failure_does_not_retry(self):
        self.status_code = 503
        self.launch()
        stdout, stderr = self.process.communicate(timeout=5)
        self.assertEqual(1, self.process.returncode)
        self.assertEqual(1, len(self.bodies))
        self.assertIn(b'Media callback failed; eventId=', stderr)

    def test_single_shot_client_error(self):
        self.status_code = 400
        self.launch()
        stdout, stderr = self.process.communicate(timeout=5)
        self.assertEqual(1, self.process.returncode)
        self.assertEqual(1, len(self.bodies))
        self.assertIn(b'Media callback failed; eventId=', stderr)

    def test_invalid_path_fails_without_request(self):
        bad_env = dict(self.environment, MTX_PATH='invalid/' + str(uuid4()))
        self.launch(env=bad_env)
        stdout, stderr = self.process.communicate(timeout=5)
        self.assertEqual(1, self.process.returncode)
        self.assertEqual(0, len(self.bodies))
        self.assertIn(b'Invalid media callback configuration', stderr)


if __name__ == '__main__':
    unittest.main()
