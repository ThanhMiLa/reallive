import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

import backend_access
import fixture


class GatewayStub:
    def __init__(self, status='LIVE'):
        self.status = status
        self.cookies = []
        self.posts = 0
        self.session_id = str(uuid4())
        self.redirect_status = None
        self.redirect_url = None
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def respond(self, status, value):
                data = json.dumps(value).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                stub.cookies.append(self.headers.get('Cookie'))
                if stub.redirect_status:
                    self.send_response(stub.redirect_status)
                    self.send_header('Location', stub.redirect_url)
                    self.end_headers()
                    return
                if self.path == '/stream/api/streams/' + stub.session_id:
                    self.respond(200, {'status': 200, 'code': 1000, 'result': {
                        'id': stub.session_id, 'status': stub.status, 'visibility': 'PUBLIC',
                        'title': 'Backend stream'}})
                else:
                    self.respond(404, {'status': 404, 'code': 1006, 'message': 'Stream session not found'})

            def do_POST(self):
                stub.cookies.append(self.headers.get('Cookie'))
                stub.posts += 1
                if stub.redirect_status:
                    self.send_response(stub.redirect_status)
                    self.send_header('Location', stub.redirect_url)
                    self.end_headers()
                    return
                if self.path == '/stream/api/streams/' + stub.session_id + '/playback-access':
                    self.respond(200, {'status': 200, 'code': 1000, 'result': {
                        'sessionId': stub.session_id,
                        'playbackUrl': 'http://localhost:8898/live/' + stub.session_id + '/index.m3u8',
                        'readToken': 'backend.read.jwt', 'readExpiresAt': '2026-10-29T03:05:00Z'}})
                else:
                    self.respond(404, {'status': 404, 'code': 1006, 'message': 'Stream session not found'})

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class BackendAccessTest(unittest.TestCase):
    def setUp(self):
        self.gateway = GatewayStub()
        self.addCleanup(self.gateway.close)

    def client(self):
        return backend_access.BackendClient(self.gateway.base, 'access_token=private-cookie')

    def test_metadata_and_access_use_the_account_cookie_and_direct_post(self):
        client = self.client()
        metadata = client.metadata(self.gateway.session_id)
        self.assertEqual('LIVE', metadata['status'])
        access = client.acquire(self.gateway.session_id)
        self.assertEqual(self.gateway.session_id, access.session_id)
        self.assertEqual('backend.read.jwt', access.read_token)
        self.assertEqual('live/' + self.gateway.session_id, access.path)
        self.assertEqual(1, self.gateway.posts)
        self.assertEqual(['access_token=private-cookie'] * 3, self.gateway.cookies)
        self.assertNotIn('backend.read.jwt', repr(access))
        self.assertNotIn('private-cookie', repr(access))

    def test_not_live_session_never_requests_playback_access(self):
        self.gateway.status = 'READY'
        with self.assertRaises(backend_access.BackendError) as context:
            self.client().acquire(self.gateway.session_id)
        self.assertEqual('STREAM_SESSION_STATE_INVALID', context.exception.code)
        self.assertEqual(0, self.gateway.posts)

    def test_api_error_is_safe_and_invalid_session_is_rejected_before_network(self):
        with self.assertRaises(backend_access.BackendError) as context:
            self.client().metadata(str(uuid4()))
        self.assertEqual(404, context.exception.status)
        self.assertNotIn('private-cookie', str(context.exception))
        with self.assertRaises(ValueError):
            self.client().metadata('not-a-uuid')

    def test_cookie_file_must_be_private_and_single_line(self):
        with tempfile.TemporaryDirectory() as directory:
            cookie = Path(directory) / 'cookie'
            cookie.write_text('access_token=private\n')
            cookie.chmod(0o644)
            with self.assertRaises(ValueError):
                backend_access.load_cookie(cookie)
            cookie.chmod(0o600)
            self.assertEqual('access_token=private', backend_access.load_cookie(cookie))
            cookie.write_text('first\nsecond\n')
            with self.assertRaises(ValueError):
                backend_access.load_cookie(cookie)

    def test_redirects_never_forward_account_cookie_or_expose_location(self):
        destination = GatewayStub()
        self.addCleanup(destination.close)
        self.gateway.redirect_url = destination.base + '/landing?secret=private-cookie'
        for status in [301, 302, 303, 307, 308]:
            self.gateway.redirect_status = status
            for method in ['GET', 'POST']:
                with self.subTest(status=status, method=method):
                    with self.assertRaises(backend_access.BackendError) as context:
                        self.client()._request(method, '/stream/api/streams/' + self.gateway.session_id)
                    self.assertEqual(502, context.exception.status)
                    self.assertEqual('Gateway redirects are not allowed', str(context.exception))
                    self.assertNotIn('private-cookie', str(context.exception))
                    self.assertEqual([], destination.cookies)

    def test_same_origin_redirects_are_not_followed_either(self):
        self.gateway.redirect_status = 302
        self.gateway.redirect_url = self.gateway.base + '/landing'
        with self.assertRaises(backend_access.BackendError):
            self.client().metadata(self.gateway.session_id)
        self.assertEqual(1, len(self.gateway.cookies))


class FakeBackend:
    def __init__(self):
        self.session_id = str(uuid4())

    def metadata(self, session_id):
        return {'id': str(session_id), 'status': 'LIVE', 'visibility': 'PUBLIC'}

    def acquire(self, session_id):
        return backend_access.BackendAccess(str(session_id), 'http://localhost:8898/live/' + str(session_id) + '/index.m3u8',
                                            '2026-10-29T03:05:00Z', 'live/' + str(session_id),
                                            {'id': str(session_id), 'status': 'LIVE'}, 'backend.read.jwt')


class FixtureBackendModeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.backend = FakeBackend()
        self.server = fixture.make_server(Path(self.directory.name), port=0, backend=self.backend)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def post(self, value, origin=True, path='/fixture/backend-access'):
        headers = {'Content-Type': 'application/json'}
        if origin:
            headers['Origin'] = self.base
        request = Request(self.base + path, data=json.dumps(value).encode(), headers=headers)
        with urlopen(request, timeout=3) as response:
            return response.status, json.load(response)

    def test_backend_access_requires_same_origin_and_returns_only_playback_credential(self):
        session = self.backend.session_id
        with self.assertRaises(HTTPError) as context:
            self.post({'sessionId': session}, origin=False)
        with context.exception:
            self.assertEqual(403, context.exception.code)
        with urlopen(self.base + '/fixture/backend-metadata?sessionId=' + session, timeout=3) as response:
            self.assertEqual('LIVE', json.load(response)['status'])
        status, result = self.post({'sessionId': session})
        self.assertEqual(200, status)
        self.assertEqual('backend.read.jwt', result['readToken'])
        self.assertEqual('live/' + session, result['path'])
        self.assertNotIn('private-cookie', json.dumps(result))

    def test_backend_access_rejects_unknown_fields(self):
        with self.assertRaises(HTTPError) as context:
            self.post({'sessionId': self.backend.session_id, 'token': 'must-not-be-accepted'})
        with context.exception:
            self.assertEqual(400, context.exception.code)

    def legacy_fixture(self):
        session = str(uuid4())
        Path(self.directory.name, 'fixtures.json').write_text(json.dumps({
            'sessionId': session, 'path': 'live/' + session}))
        return session

    def observation(self, **context):
        return dict(kind='browser', case='valid', frames=45, seconds=3.0,
                    audioConfirmed=True, playlistLoads=2, segmentLoads=2, httpError=0, **context)

    def test_browser_evidence_uses_backend_session_and_source_not_legacy_fixture(self):
        legacy = self.legacy_fixture()
        session = self.backend.session_id
        self.assertNotEqual(legacy, session)
        self.post({'sessionId': session})
        source = {'id': str(uuid4()), 'type': 'rtmpConn'}
        def media_response(state, method, endpoint):
            self.assertTrue(endpoint.endswith('/live/' + session))
            return (200, {'name': 'live/' + session, 'source': source, 'online': True})
        with patch('media.request', side_effect=media_response):
            self.assertEqual(200, self.post(self.observation(
                accessSource='backend', sessionId=session), path='/fixture/observation')[0])
        saved = json.loads(Path(self.directory.name, 'manual-results.jsonl').read_text())
        self.assertEqual(session, saved['sessionId'])
        self.assertEqual('backend', saved['accessSource'])
        self.assertEqual(source, saved['source'])
        self.assertEqual('live/' + session, saved['media']['name'])

    def test_backend_evidence_does_not_require_legacy_fixture_state(self):
        session = self.backend.session_id
        self.post({'sessionId': session})
        with patch('media.request', return_value=(404, None)):
            self.assertEqual(200, self.post(self.observation(
                accessSource='backend', sessionId=session), path='/fixture/observation')[0])
        saved = json.loads(Path(self.directory.name, 'manual-results.jsonl').read_text())
        self.assertEqual(session, saved['sessionId'])

    def test_backend_mode_rejects_ambiguous_or_ungranted_browser_evidence(self):
        legacy = self.legacy_fixture()
        invalid = [self.observation(), self.observation(accessSource='backend', sessionId=str(uuid4())),
                   self.observation(accessSource='fixture', sessionId=str(uuid4())),
                   self.observation(accessSource='unknown', sessionId=legacy),
                   self.observation(accessSource='backend', sessionId='not-a-uuid'),
                   self.observation(accessSource='fixture')]
        with patch('media.request') as media_request:
            for value in invalid:
                with self.subTest(value=value), self.assertRaises(HTTPError) as context:
                    self.post(value, path='/fixture/observation')
                with context.exception:
                    self.assertEqual(400, context.exception.code)
            media_request.assert_not_called()
        self.assertFalse(Path(self.directory.name, 'manual-results.jsonl').exists())

    def test_explicit_fixture_evidence_still_records_the_legacy_session(self):
        legacy = self.legacy_fixture()
        with patch('media.request', return_value=(404, None)):
            self.assertEqual(200, self.post(self.observation(
                accessSource='fixture', sessionId=legacy), path='/fixture/observation')[0])
        saved = json.loads(Path(self.directory.name, 'manual-results.jsonl').read_text())
        self.assertEqual(legacy, saved['sessionId'])
        self.assertEqual('fixture', saved['accessSource'])

    def test_backend_obs_before_player_and_acceptance_use_session_b(self):
        legacy = self.legacy_fixture()
        session = self.backend.session_id
        source = {'id': str(uuid4()), 'type': 'rtmpConn'}
        def media_response(state, method, endpoint):
            self.assertTrue(endpoint.endswith('/live/' + session))
            return 200, {'name': 'live/' + session, 'online': True, 'source': source}
        with patch('media.request', side_effect=media_response):
            self.post({'kind': 'obs', 'step': 'publish', 'accessSource': 'backend',
                       'sessionId': session}, path='/fixture/observation')
            with urlopen(self.base + '/fixture/status?accessSource=backend&sessionId=' + session) as response:
                status = json.load(response)
        self.assertEqual(session, status['sessionId'])
        self.assertEqual('backend', status['accessSource'])
        self.assertEqual('LIVE', status['status'])
        saved = json.loads(Path(self.directory.name, 'manual-results.jsonl').read_text())
        self.assertEqual(session, saved['sessionId'])
        self.assertNotEqual(legacy, saved['sessionId'])
        self.assertEqual('LIVE', saved['backendStatus'])
        self.assertEqual(source, saved['source'])
        with urlopen(self.base + '/fixture/acceptance?accessSource=backend&sessionId=' + session) as response:
            results = json.load(response)
        self.assertTrue(results['obs-publish']['passed'])
        self.assertFalse(results['browser']['passed'])
        self.assertTrue(all(result['sessionId'] == session for result in results.values()))
        other = str(uuid4())
        with urlopen(self.base + '/fixture/acceptance?accessSource=backend&sessionId=' + other) as response:
            self.assertTrue(all(not result['passed'] for result in json.load(response).values()))

    def test_backend_mode_rejects_obs_without_context(self):
        self.legacy_fixture()
        with patch('media.request') as media_request:
            with self.assertRaises(HTTPError) as context:
                self.post({'kind': 'obs', 'step': 'publish'}, path='/fixture/observation')
            with context.exception:
                self.assertEqual(400, context.exception.code)
            media_request.assert_not_called()

    def test_backend_obs_end_uses_backend_status_and_closed_path(self):
        session = self.backend.session_id
        with patch.object(self.backend, 'metadata', return_value={'id': session, 'status': 'ENDED'}):
            with patch('media.request', return_value=(404, None)):
                self.post({'kind': 'obs', 'step': 'old-key-rejected', 'accessSource': 'backend',
                           'sessionId': session}, path='/fixture/observation')
        with urlopen(self.base + '/fixture/acceptance?accessSource=backend&sessionId=' + session) as response:
            self.assertTrue(json.load(response)['obs-end']['passed'])

    def test_acceptance_rejects_incomplete_session_selection_instead_of_reading_a(self):
        self.legacy_fixture()
        for query in ['sessionId=' + self.backend.session_id, 'accessSource=backend',
                      'accessSource=backend&sessionId=not-a-uuid']:
            with self.subTest(query=query), self.assertRaises(HTTPError) as context:
                urlopen(self.base + '/fixture/acceptance?' + query)
            with context.exception:
                self.assertEqual(400, context.exception.code)

    def test_full_backend_evidence_round_trip_never_completes_another_session(self):
        legacy = self.legacy_fixture()
        session = self.backend.session_id
        self.post({'sessionId': session})
        context = {'accessSource': 'backend', 'sessionId': session}
        source = {'id': str(uuid4()), 'type': 'rtmpConn'}
        def media_response(state, method, endpoint):
            self.assertTrue(endpoint.endswith('/live/' + session))
            return 200, {'source': source, 'online': True}
        with patch('media.request', side_effect=media_response):
            self.post(dict(kind='obs', step='publish', **context), path='/fixture/observation')
            self.post(self.observation(**context), path='/fixture/observation')
            source = {'id': str(uuid4()), 'type': 'rtmpConn'}
            self.post(dict(kind='obs', step='reconnect', **context), path='/fixture/observation')
        with patch.object(self.backend, 'metadata', return_value={'id': session, 'status': 'ENDED'}):
            with patch('media.request', return_value=(404, None)):
                self.post(dict(kind='obs', step='old-key-rejected', **context), path='/fixture/observation')
        for selected, expected in [(session, True), (legacy, False)]:
            with urlopen(self.base + '/fixture/acceptance?accessSource=backend&sessionId=' + selected) as response:
                result = json.load(response)
            self.assertTrue(all(check['passed'] == expected for check in result.values()))
            self.assertTrue(all(check['sessionId'] == selected for check in result.values()))


if __name__ == '__main__':
    unittest.main()
