from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from uuid import UUID, uuid4


HOOK = Path(__file__).resolve().parents[3] / 'media-server/hooks/viewer_hook.py'


class ViewerHookTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.token_file = Path(self.directory.name) / 'control.jwt'
        self.token_file.write_text('fixture-control-token-do-not-log\n')
        self.control_requests = []
        self.callback_requests = []
        self.callback_gets = []
        self.control_statuses = [200]
        self.callback_statuses = [200]
        self.control_delay = 0
        self.callback_delay = 0
        self.lookup_received = threading.Event()
        self.control_action = lambda: None
        self.control_body = None
        self.session_id = str(uuid4())
        self.reader_id = str(uuid4())
        self.user_id = str(uuid4())
        self.control_value = {
            'id': self.reader_id,
            'path': 'live/' + self.session_id,
            'user': self.user_id,
        }
        self.process = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def reply(self, status, body, location=None):
                if status is None:
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                try:
                    self.send_response(status)
                    if location:
                        self.send_header('Location', location)
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except OSError:
                    pass

            def do_GET(self):
                if self.server is owner.callback_server:
                    owner.callback_gets.append(self.path)
                    self.reply(200, b'{}')
                    return
                owner.control_requests.append({
                    'path': self.path,
                    'authorization': self.headers.get('Authorization'),
                })
                owner.lookup_received.set()
                owner.control_action()
                time.sleep(owner.control_delay)
                status = owner.next_status(owner.control_statuses)
                body = owner.control_body
                if body is None:
                    body = json.dumps(owner.control_value).encode()
                self.reply(status, body, owner.callback_url + '/leak')

            def do_POST(self):
                owner.callback_requests.append({
                    'path': self.path,
                    'body': self.rfile.read(int(self.headers['Content-Length'])),
                    'token': self.headers.get('X-Internal-Token'),
                    'authorization': self.headers.get('Authorization'),
                })
                time.sleep(owner.callback_delay)
                self.reply(owner.next_status(owner.callback_statuses),
                           b'{"error":"fixture-server-body-do-not-log"}',
                           owner.control_base + '/leak')

        self.control_server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.callback_server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.control_base = 'http://127.0.0.1:' + str(self.control_server.server_port)
        self.callback_url = 'http://127.0.0.1:' + str(self.callback_server.server_port) + '/stream/internal/api/viewer-events'
        self.threads = []
        for server in [self.control_server, self.callback_server]:
            server.daemon_threads = True
            thread = threading.Thread(target=lambda s=server: s.serve_forever(poll_interval=0.01), daemon=True)
            thread.start()
            self.threads.append(thread)
        self.environment = dict(os.environ,
                                STREAM_CCU_ENABLED='true',
                                MEDIA_CONTROL_BASE_URL=self.control_base,
                                MEDIA_CONTROL_TOKEN_FILE=str(self.token_file),
                                STREAM_VIEWER_CALLBACK_URL=self.callback_url,
                                MTX_PATH='live/' + self.session_id,
                                MTX_READER_ID=self.reader_id,
                                MTX_READER_TYPE='hlsSession',
                                MTX_QUERY='token=fixture-query-token-do-not-log')

    def tearDown(self):
        if self.process and self.process.poll() is None:
            self.process.kill()
            self.process.communicate()
        for server in [self.control_server, self.callback_server]:
            server.shutdown()
            server.server_close()
        for thread in self.threads:
            thread.join()
        self.directory.cleanup()

    @staticmethod
    def next_status(statuses):
        return statuses.pop(0) if len(statuses) > 1 else statuses[0]

    def launch(self, action='read', environment=None):
        self.process = subprocess.Popen([sys.executable, str(HOOK), action],
                                        env=environment if environment is not None else self.environment,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def finish(self, expected=0, timeout=12):
        stdout, stderr = self.process.communicate(timeout=timeout)
        self.assertEqual(expected, self.process.returncode, stderr.decode())
        for value in [b'fixture-control-token-do-not-log', b'fixture-internal-token-do-not-log',
                      b'fixture-query-token-do-not-log', b'fixture-server-body-do-not-log']:
            self.assertNotIn(value, stdout + stderr)
            self.assertNotIn(value, b''.join(request['body'] for request in self.callback_requests))
        return stdout, stderr

    def test_read_enriches_identity_and_sends_uncredentialed_internal_callback(self):
        self.launch()
        self.finish()
        self.assertEqual([{'path': '/v3/hls/sessions/get/' + self.reader_id,
                           'authorization': 'Bearer fixture-control-token-do-not-log'}], self.control_requests)
        self.assertEqual(1, len(self.callback_requests))
        callback = self.callback_requests[0]
        self.assertEqual('/stream/internal/api/viewer-events', callback['path'])
        self.assertIsNone(callback['token'])
        self.assertIsNone(callback['authorization'])
        event = json.loads(callback['body'])
        self.assertEqual({'eventId', 'eventType', 'sessionId', 'readerId', 'readerType', 'occurredAt', 'userId'}, set(event))
        UUID(event['eventId'])
        self.assertIsNotNone(datetime.fromisoformat(event['occurredAt']).tzinfo)
        self.assertEqual('READ', event['eventType'])
        self.assertEqual(self.user_id, event['userId'])
        self.assertEqual(self.reader_id, event['readerId'])
        self.assertEqual(self.session_id, event['sessionId'])
        self.assertEqual('hlsSession', event['readerType'])

    def test_unread_never_looks_up_user_or_reads_control_token(self):
        self.token_file.unlink()
        self.launch('unread')
        self.finish()
        self.assertEqual([], self.control_requests)
        event = json.loads(self.callback_requests[0]['body'])
        self.assertEqual('UNREAD', event['eventType'])
        self.assertEqual(self.reader_id, event['readerId'])
        self.assertNotIn('userId', event)

    def test_disabled_by_default_without_configuration_or_network(self):
        self.launch(environment={'PATH': os.environ.get('PATH', '')})
        self.finish()
        self.assertEqual([], self.control_requests + self.callback_requests)

    def test_non_hls_reader_is_ignored(self):
        self.launch(environment=dict(self.environment, MTX_READER_TYPE='rtmpConn'))
        self.finish()
        self.assertEqual([], self.control_requests + self.callback_requests)

    def test_invalid_ids_path_and_callback_configuration_do_not_make_requests(self):
        for changes in [dict(MTX_PATH='wrong/' + self.session_id), dict(MTX_PATH='live/not-a-uuid'),
                        dict(MTX_READER_ID='not-a-uuid'),
                        dict(STREAM_VIEWER_CALLBACK_URL='http://user:secret@127.0.0.1/callback')]:
            with self.subTest(changes=list(changes)):
                self.launch(environment=dict(self.environment, **changes))
                self.finish(expected=1)
        self.assertEqual([], self.control_requests + self.callback_requests)

    def test_lookup_404_ignores_reader_without_callback(self):
        self.control_statuses = [404]
        self.launch()
        self.finish()
        self.assertEqual(1, len(self.control_requests))
        self.assertEqual([], self.callback_requests)

    def test_technical_identity_does_not_count_as_viewer(self):
        self.control_value['user'] = 'media-worker'
        self.launch()
        self.finish()
        self.assertEqual([], self.callback_requests)

    def test_lookup_requires_matching_reader_and_stream(self):
        for field, value in [('id', str(uuid4())), ('path', 'live/' + str(uuid4())), ('user', None)]:
            with self.subTest(field=field):
                previous = self.control_value[field]
                self.control_value[field] = value
                self.launch()
                self.finish(expected=1)
                self.control_value[field] = previous
        self.assertEqual([], self.callback_requests)

    def test_invalid_json_or_oversized_response_is_rejected(self):
        for body in [b'not-json', b'[]', b'x' * 16385]:
            with self.subTest(size=len(body)):
                self.control_body = body
                self.launch()
                self.finish(expected=1)
        self.assertEqual([], self.callback_requests)

    def test_missing_control_token_fails_without_lookup(self):
        self.token_file.unlink()
        self.launch()
        self.finish(expected=1)
        self.assertEqual([], self.control_requests + self.callback_requests)

    def test_control_unauthorized_reloads_token_once(self):
        self.control_statuses = [401, 200]
        self.control_action = lambda: self.token_file.write_text('fixture-rotated-token')
        self.launch()
        self.finish()
        self.assertEqual(['Bearer fixture-control-token-do-not-log', 'Bearer fixture-rotated-token'],
                         [request['authorization'] for request in self.control_requests])
        self.assertEqual(1, len(self.callback_requests))

    def test_control_repeated_unauthorized_stops_after_one_reload(self):
        self.control_statuses = [401]
        self.launch()
        self.finish(expected=1)
        self.assertEqual(2, len(self.control_requests))
        self.assertEqual([], self.callback_requests)

    def test_transient_control_failure_retries(self):
        self.control_statuses = [503, None, 200]
        self.launch()
        self.finish()
        self.assertEqual(3, len(self.control_requests))
        self.assertEqual(1, len(self.callback_requests))

    def test_callback_retry_keeps_identical_body_for_unavailable_and_ready(self):
        self.callback_statuses = [503, 409, 200]
        self.launch()
        self.finish()
        self.assertEqual(1, len(self.control_requests))
        self.assertEqual(3, len(self.callback_requests))
        self.assertEqual(1, len(set(request['body'] for request in self.callback_requests)))

    def test_callback_transport_retry_keeps_identical_body(self):
        self.callback_statuses = [None, 200]
        self.launch('unread')
        self.finish()
        self.assertEqual([], self.control_requests)
        self.assertEqual(2, len(self.callback_requests))
        self.assertEqual(self.callback_requests[0]['body'], self.callback_requests[1]['body'])

    def test_callback_client_error_does_not_retry(self):
        for status in [400, 401, 403]:
            with self.subTest(status=status):
                self.callback_statuses = [status]
                before = len(self.callback_requests)
                self.launch()
                self.finish(expected=1)
                self.assertEqual(before + 1, len(self.callback_requests))

    def test_callback_retries_are_bounded(self):
        self.callback_statuses = [503]
        self.launch()
        self.finish(expected=1)
        self.assertEqual(3, len(self.callback_requests))

    def test_control_and_callback_redirects_never_forward_credentials(self):
        self.control_statuses = [302]
        self.launch()
        self.finish(expected=1)
        self.assertEqual([], self.callback_gets + self.callback_requests)
        self.control_statuses = [200]
        self.callback_statuses = [302]
        before = len(self.control_requests)
        self.launch()
        self.finish(expected=1)
        self.assertEqual(before + 1, len(self.control_requests))

    def test_sigint_cancels_pending_lookup_without_late_read_callback(self):
        self.control_delay = 1
        self.launch()
        self.assertTrue(self.lookup_received.wait(3))
        self.process.send_signal(signal.SIGINT)
        self.finish(timeout=3)
        self.assertEqual([], self.callback_requests)

    @unittest.skipUnless(hasattr(signal, 'setitimer'), 'requires POSIX request deadline')
    def test_entire_hook_respects_shared_ten_second_deadline(self):
        self.control_statuses = [503, 503, 200]
        self.control_delay = 1.7
        self.callback_statuses = [503]
        self.callback_delay = 4
        started = time.monotonic()
        self.launch()
        _, stderr = self.finish(expected=1, timeout=12)
        elapsed = time.monotonic() - started
        self.assertLess(elapsed, 11.5)
        self.assertIn(b'deadline-exceeded', stderr)
        self.assertEqual(3, len(self.control_requests))


if __name__ == '__main__':
    unittest.main()
