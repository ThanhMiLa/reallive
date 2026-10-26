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
        self.failures = 2
        owner = self

        class Receiver(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                owner.bodies.append(body)
                status = 503 if len(owner.bodies) <= owner.failures else 200
                self.send_response(status)
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

    def launch(self):
        import sys
        self.process = subprocess.Popen([sys.executable, str(fixture.REPO / 'infra/mediamtx/media_hook.py'), 'online'],
                                        env=self.environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_retry_keeps_body_and_event_id(self):
        self.launch()
        stdout, stderr = self.process.communicate(timeout=8)
        self.assertEqual(0, self.process.returncode)
        self.assertEqual(3, len(self.bodies))
        self.assertEqual(1, len(set(self.bodies)))
        event = json.loads(self.bodies[0])
        self.assertEqual('ONLINE', event['eventType'])
        self.assertEqual(self.environment['MTX_SOURCE_ID'], event['sourceId'])
        self.assertNotIn(b'must-never-be-sent', b''.join(self.bodies) + stdout + stderr)
        self.assertNotIn('MTX_QUERY', event)

    def test_sigint_stops_retry(self):
        self.failures = 100
        self.launch()
        self.assertTrue(self.received.wait(timeout=5))
        self.process.send_signal(signal.SIGINT)
        self.process.communicate(timeout=4)
        self.assertEqual(1, self.process.returncode)
        self.assertEqual(1, len(self.bodies))


if __name__ == '__main__':
    unittest.main()
