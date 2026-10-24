import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

import fixture


class FixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.state = Path(cls.directory.name)
        fixture.prepare(cls.state)
        cls.fixtures = json.loads((cls.state / 'fixtures.json').read_text())
        cls.server = fixture.make_server(cls.state, port=0)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.directory.cleanup()

    def test_key_generation_is_stable_and_secrets_are_private(self):
        paths = [self.state / name for name in ['private.pem', 'public.pem', 'callback.secret']]
        before = [hashlib.sha256(path.read_bytes()).digest() for path in paths]
        subprocess.run([str(fixture.REPO / 'scripts/dev/generate-stream-media-keys.sh'), str(self.state)], check=True)
        self.assertEqual(before, [hashlib.sha256(path.read_bytes()).digest() for path in paths])
        self.assertTrue(all(path.stat().st_mode & 0o077 == 0 for path in paths))

    def test_incomplete_key_pair_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory) / 'private.pem'
            private.write_text('existing key must survive')
            result = subprocess.run([str(fixture.REPO / 'scripts/dev/generate-stream-media-keys.sh'), directory], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('existing key must survive', private.read_text())

    def test_valid_tokens_have_exact_permissions_and_expiry(self):
        for action, ttl in [('publish', 86400), ('read', 900), ('api', 300)]:
            with self.subTest(action=action):
                payload = fixture.verify(self.state, self.fixtures[action + 'Token'], action, self.fixtures['path'])
                self.assertEqual(ttl, payload['exp'] - payload['iat'])
                self.assertEqual(1, len(payload['mediamtx_permissions']))

    def test_invalid_signature_expiry_path_and_action_are_rejected(self):
        for name, token in self.fixtures['negativeTokens'].items():
            action = 'publish' if name.endswith('Publish') else 'read'
            with self.subTest(name=name), self.assertRaises(ValueError):
                fixture.verify(self.state, token, action, self.fixtures['path'])
        with self.assertRaises(ValueError):
            fixture.verify(self.state, self.fixtures['readToken'], 'publish', self.fixtures['path'])
        with self.assertRaises(ValueError):
            fixture.verify(self.state, self.fixtures['publishToken'], 'api', '')

    def test_jwks_endpoint_contains_only_public_parameters(self):
        with urlopen(self.base + fixture.JWKS_PATH, timeout=3) as response:
            key = json.load(response)['keys'][0]
        self.assertEqual({'kty', 'kid', 'alg', 'use', 'n', 'e'}, set(key))
        self.assertEqual(65537, int.from_bytes(fixture.unb64url(key['e']), 'big'))
        self.assertGreaterEqual(int.from_bytes(fixture.unb64url(key['n']), 'big').bit_length(), 2048)

    def send_event(self, body, timestamp=None, signature=None):
        timestamp = str(int(time.time())) if timestamp is None else timestamp
        secret = (self.state / 'callback.secret').read_bytes().strip()
        signature = fixture.callback_signature(secret, timestamp, body) if signature is None else signature
        request = Request(self.base + fixture.EVENTS_PATH, data=body, headers={
            'Content-Type': 'application/json', 'X-Media-Timestamp': timestamp, 'X-Media-Signature': signature})
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            with error:
                return error.code, json.load(error)

    def event_body(self):
        return json.dumps({'eventId': str(uuid4()), 'eventType': 'ONLINE', 'path': self.fixtures['path'],
                           'sourceId': str(uuid4()), 'sourceType': 'rtmpConn',
                           'occurredAt': '2026-10-24T00:00:00Z'}).encode()

    def test_signed_callback_and_duplicate_are_recorded_once(self):
        body = self.event_body()
        self.assertEqual((200, {'accepted': True, 'duplicate': False}), self.send_event(body))
        self.assertEqual((200, {'accepted': True, 'duplicate': True}), self.send_event(body))
        event_id = json.loads(body)['eventId']
        records = [json.loads(line) for line in (self.state / 'events.jsonl').read_text().splitlines()]
        self.assertEqual(1, sum(record['eventId'] == event_id for record in records))

    def test_invalid_hmac_stale_timestamp_and_tampered_body_are_rejected(self):
        body = self.event_body()
        self.assertEqual(401, self.send_event(body, signature='sha256=bad')[0])
        self.assertEqual(401, self.send_event(body, signature='sha256=é')[0])
        self.assertEqual(401, self.send_event(body, timestamp=str(int(time.time()) - 120))[0])
        timestamp = str(int(time.time()))
        signature = fixture.callback_signature((self.state / 'callback.secret').read_bytes().strip(), timestamp, body)
        self.assertEqual(401, self.send_event(body + b' ', timestamp=timestamp, signature=signature)[0])

    def test_query_and_oversize_payload_are_rejected(self):
        event = json.loads(self.event_body())
        event['MTX_QUERY'] = 'must-not-be-recorded'
        self.assertEqual(400, self.send_event(json.dumps(event).encode())[0])
        self.assertEqual(413, self.send_event(b'x' * (fixture.MAX_BODY + 1))[0])

    def fixture_post(self, path, value, origin=True):
        headers = {'Content-Type': 'application/json'}
        if origin:
            headers['Origin'] = self.base if origin is True else origin
        request = Request(self.base + path, data=json.dumps(value).encode(), headers=headers)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            with error:
                return error.code, json.load(error)

    def test_browser_fixture_requires_same_local_origin(self):
        value = {'kind': 'read', 'case': 'valid'}
        self.assertEqual(403, self.fixture_post('/fixture/credentials', value, origin=False)[0])
        self.assertEqual(403, self.fixture_post('/fixture/credentials', value, origin='http://untrusted.invalid')[0])

    def test_browser_fixture_credentials_do_not_expose_api_token(self):
        with patch('media.request', return_value=(200, {})):
            status, result = self.fixture_post('/fixture/credentials', {'kind': 'read', 'case': 'valid'})
            self.assertEqual(200, status)
            self.assertEqual({'playbackUrl', 'readToken', 'path'}, set(result))
            fixture.verify(self.state, result['readToken'], 'read', self.fixtures['path'])
            self.assertEqual(400, self.fixture_post('/fixture/credentials', {'kind': 'api', 'case': 'valid'})[0])

    def test_credentials_require_provisioned_path_and_support_negative_cases(self):
        with patch('media.request', return_value=(404, None)):
            self.assertEqual(409, self.fixture_post('/fixture/credentials', {'kind': 'publish', 'case': 'valid'})[0])
        with patch('media.request', return_value=(200, {})):
            status, result = self.fixture_post('/fixture/credentials', {'kind': 'read', 'case': 'wrong-action'})
            self.assertEqual(200, status)
            fixture.verify(self.state, result['readToken'], 'publish', self.fixtures['path'])

    def test_browser_observation_rejects_unknown_secret_fields(self):
        value = {'kind': 'browser', 'case': 'valid', 'frames': 45, 'seconds': 3.0,
                 'audioConfirmed': True, 'playlistLoads': 2, 'segmentLoads': 2, 'httpError': 0}
        with patch('media.request', return_value=(404, None)):
            self.assertEqual(400, self.fixture_post('/fixture/observation', dict(value, token='must-not-be-recorded'))[0])
            self.assertEqual(200, self.fixture_post('/fixture/observation', value)[0])
        saved = (self.state / 'manual-results.jsonl').read_text()
        self.assertNotIn('must-not-be-recorded', saved)
        self.assertTrue(json.loads(saved)['audioConfirmed'])

    def test_player_page_is_available_without_returning_credentials(self):
        with urlopen(self.base + '/', timeout=3) as response:
            body = response.read().decode()
            self.assertEqual('no-store', response.headers['Cache-Control'])
        self.assertIn('player.js', body)
        self.assertNotIn(self.fixtures['publishToken'], body)
        self.assertNotIn(self.fixtures['readToken'], body)

    def test_acceptance_does_not_complete_tasks_without_runtime_and_human_evidence(self):
        from acceptance import evaluate
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            fixture.write_private_json(state / 'session.json', {'sessionId': self.fixtures['sessionId']})
            (state / 'manual-results.jsonl').write_text(json.dumps({
                'sessionId': self.fixtures['sessionId'], 'kind': 'obs', 'step': 'publish', 'source': None}) + '\n')
            fixture.write_private_json(state / 'user-confirmation.json', {
                'source': 'user-message', 'sessionId': self.fixtures['sessionId'],
                'statement': 'Manual workflow completed',
                'confirmedChecks': ['browser-rejection:missing', 'browser-rejection:wrong-action', 'browser-rejection:wrong-path']})
            result = evaluate(state)
            self.assertTrue(all(item['passed'] is False for item in result.values()))


if __name__ == '__main__':
    unittest.main()
