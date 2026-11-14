#!/usr/bin/env python3
"""Local technical fixtures for Streaming MVP; never use as production auth."""

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
from uuid import UUID, uuid4

from backend_access import BackendClient, BackendError, load_cookie

REPO = Path(__file__).resolve().parents[3]
DEFAULT_STATE = REPO / '.local/streaming'
ISSUER = 'reallive-stream'
AUDIENCE = 'reallive-media'
KEY_ID = 'stream-rs256-v1'
READ_TTL = 5 * 60
JWKS_PATH = '/stream/internal/api/media/jwks'
EVENTS_PATH = '/stream/internal/api/media-events'
MAX_BODY = 16384


def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')


def unb64url(value):
    return base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True)


def openssl(*args, data=None):
    result = subprocess.run(['openssl', *map(str, args)], input=data, capture_output=True)
    if result.returncode:
        raise ValueError('OpenSSL operation failed: ' + str(args[0]))
    return result.stdout


def write_private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(value, output, indent=2)
            output.write('\n')
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_der_item(data, offset):
    tag, length = data[offset], data[offset + 1]
    offset += 2
    if length & 128:
        width = length & 127
        length = int.from_bytes(data[offset:offset + width], 'big')
        offset += width
    return tag, data[offset:offset + length], offset + length


def jwks(state):
    # OpenSSL supplies a PKCS#1 SEQUENCE of modulus/exponent INTEGER values.
    der = openssl('rsa', '-pubin', '-in', state / 'public.pem', '-RSAPublicKey_out', '-outform', 'DER')
    tag, sequence, end = read_der_item(der, 0)
    if tag != 48 or end != len(der):
        raise ValueError('Invalid RSA public key')
    tag_n, modulus, offset = read_der_item(sequence, 0)
    tag_e, exponent, end = read_der_item(sequence, offset)
    if tag_n != 2 or tag_e != 2 or end != len(sequence):
        raise ValueError('Invalid RSA public parameters')
    return {'keys': [{'kty': 'RSA', 'kid': KEY_ID, 'alg': 'RS256', 'use': 'sig',
                      'n': b64url(modulus.lstrip(b'\0')), 'e': b64url(exponent.lstrip(b'\0'))}]}


def issue(state, action, path, ttl=None, now=None, subject=None):
    now = int(time.time()) if now is None else now
    if subject is None:
        subject = '22222222-2222-4222-8222-222222222222' if action == 'read' else '11111111-1111-4111-8111-111111111111'
    permission = {'action': action, 'path': path}
    header = {'alg': 'RS256', 'kid': KEY_ID, 'typ': 'JWT'}
    payload = {'iss': ISSUER, 'aud': AUDIENCE, 'sub': subject, 'iat': now,
               'jti': str(uuid4()), 'mediamtx_permissions': [permission]}
    if ttl is not None:
        payload['exp'] = now + ttl
    unsigned = '.'.join(b64url(json.dumps(item, separators=(',', ':')).encode()) for item in [header, payload])
    signature = openssl('dgst', '-sha256', '-sign', state / 'private.pem', data=unsigned.encode())
    return unsigned + '.' + b64url(signature)


def verify(state, token, action, path, now=None, allow_permanent=None):
    """Independently verify signature and expected fixture claims/permissions."""
    parts = token.split('.')
    if len(parts) != 3:
        raise ValueError('Malformed JWT')
    header, payload = (json.loads(unb64url(part)) for part in parts[:2])
    if header.get('alg') != 'RS256' or header.get('kid') != KEY_ID:
        raise ValueError('Unexpected JWT header')
    with tempfile.TemporaryDirectory() as directory:
        signature = Path(directory) / 'signature.bin'
        signature.write_bytes(unb64url(parts[2]))
        openssl('dgst', '-sha256', '-verify', state / 'public.pem', '-signature', signature,
                data='.'.join(parts[:2]).encode())
    now = int(time.time()) if now is None else now
    if payload.get('iss') != ISSUER or payload.get('aud') != AUDIENCE:
        raise ValueError('Unexpected issuer/audience')
    permanent_allowed = (action == 'publish') if allow_permanent is None else allow_permanent
    if 'exp' in payload:
        if payload.get('exp', 0) <= now:
            raise ValueError('Expired JWT')
    elif not permanent_allowed:
        raise ValueError('Missing JWT expiry')
    expected = {'action': action, 'path': path}
    if payload.get('mediamtx_permissions') != [expected]:
        raise ValueError('Unexpected permissions')
    return payload


def corrupt_signature(token):
    header, payload, signature = token.split('.')
    raw = bytearray(unb64url(signature))
    raw[0] ^= 1
    return '.'.join([header, payload, b64url(raw)])


def prepare(state):
    subprocess.run([str(REPO / 'scripts/dev/generate-stream-media-keys.sh'), str(state)], check=True)
    session_file = state / 'session.json'
    existing = json.loads(session_file.read_text()) if session_file.exists() else {}
    session_id = str(UUID(existing['sessionId'])) if 'sessionId' in existing else str(uuid4())
    ingest_key_id = str(UUID(existing['ingestKeyId'])) if 'ingestKeyId' in existing else str(uuid4())
    if existing.get('sessionId') != session_id or existing.get('ingestKeyId') != ingest_key_id:
        write_private_json(session_file, {'sessionId': session_id, 'ingestKeyId': ingest_key_id})
    ingest_path = 'ingest/' + ingest_key_id
    path = 'live/' + session_id
    publish = issue(state, 'publish', ingest_path, None)
    relay_read = issue(state, 'read', ingest_path, None, subject='relay-internal')
    read = issue(state, 'read', path, READ_TTL)
    other_ingest = 'ingest/' + str(uuid4())
    other_path = 'live/' + str(uuid4())
    fixtures = {'sessionId': session_id, 'ingestKeyId': ingest_key_id,
                'ingestPath': ingest_path, 'path': path,
                'serverUrl': 'rtmp://localhost:1935/ingest',
                'streamKey': ingest_key_id + '?token=' + publish,
                'playbackUrl': 'http://localhost:8898/' + path + '/index.m3u8',
                'publishToken': publish, 'relayReadToken': relay_read,
                'readToken': read,
                'negativeTokens': {
                    'expiredPublish': issue(state, 'publish', ingest_path, -1),
                    'expiredRead': issue(state, 'read', path, -1),
                    'wrongPathPublish': issue(state, 'publish', other_ingest, None),
                    'wrongPathRead': issue(state, 'read', other_path, READ_TTL),
                    'wrongSignaturePublish': corrupt_signature(publish),
                    'wrongSignatureRead': corrupt_signature(read)}}
    write_private_json(state / 'fixtures.json', fixtures)
    write_private_json(state / 'jwks.json', jwks(state))
    environment = state / 'media-server.env'
    descriptor = os.open(environment, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        output.write('# Media Server env\n')
    print('Prepared fixtures.json and public-only jwks.json; token values were not printed.', flush=True)


def callback_signature(secret, timestamp, body):
    digest = hmac.new(secret, timestamp.encode() + b'\n' + body, hashlib.sha256).hexdigest()
    return 'sha256=' + digest


def validate_event(event):
    expected = {'eventId', 'eventType', 'path', 'sourceId', 'sourceType', 'occurredAt'}
    if not isinstance(event, dict) or set(event) != expected:
        raise ValueError('Unexpected callback fields')
    UUID(event['eventId'])
    UUID(event['sourceId'])
    if not re.fullmatch(r'ingest/[0-9a-f-]{36}', event['path']):
        raise ValueError('Invalid callback path')
    UUID(event['path'][len('ingest/'):])
    if event['eventType'] not in ['ONLINE', 'OFFLINE'] or event['sourceType'] not in ['rtmpConn', 'rtmpsConn']:
        raise ValueError('Invalid callback type')
    occurred = datetime.fromisoformat(event['occurredAt'].replace('Z', '+00:00'))
    if occurred.tzinfo is None:
        raise ValueError('Callback time requires timezone')


def make_server(state, host='127.0.0.1', port=18082, backend=None):
    events_file = state / 'events.jsonl'
    seen = set()
    if events_file.exists():
        seen.update(json.loads(line)['eventId'] for line in events_file.read_text().splitlines() if line)
    event_lock = threading.Lock()
    backend_sessions = set()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            # Base logging can expose request queries. Fixtures log only safe event IDs.
            pass

        def fixture_host_allowed(self):
            host = self.headers.get('Host', '')
            allowed = ['localhost:' + str(self.server.server_port), '127.0.0.1:' + str(self.server.server_port)]
            if host not in allowed:
                return False
            origin = self.headers.get('Origin')
            return origin is None or origin == 'http://' + host

        def backend_metadata(self):
            if backend is None:
                self.respond(503, {'error': 'backend_mode_disabled'})
                return
            if not self.fixture_host_allowed():
                self.respond(403, {'error': 'fixture_requires_same_local_origin'})
                return
            session_id = parse_qs(urlsplit(self.path).query).get('sessionId', [None])[0]
            if not session_id:
                self.respond(400, {'error': 'session_id_required'})
                return
            try:
                metadata = backend.metadata(session_id)
                self.respond(200, metadata)
            except (BackendError, ValueError) as error:
                self.backend_error(error, 'backend_metadata_failed')

        def backend_error(self, error, fallback):
            status = getattr(error, 'status', None) or 502
            code = getattr(error, 'code', None)
            detail = {'error': fallback}
            if code is not None:
                detail['code'] = code
            self.respond(status, detail)

        def acceptance_context(self):
            query = parse_qs(urlsplit(self.path).query)
            source = query.get('accessSource', ['fixture'])[0]
            if source == 'fixture':
                if 'sessionId' in query:
                    raise ValueError('Session selection requires backend mode')
                return source, None
            if source != 'backend':
                raise ValueError('Invalid access source')
            if backend is None:
                raise BackendError('Backend mode disabled', status=503)
            return source, str(UUID(query.get('sessionId', [''])[0]))

        def respond(self, status, value):
            data = json.dumps(value).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('X-Frame-Options', 'DENY')
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == JWKS_PATH:
                self.respond(200, jwks(state))
            elif self.path == '/health':
                self.respond(200, {'status': 'ok', 'fixture': True})
            elif self.path in ['/', '/player.js', '/hls.min.js']:
                files = {'/': (REPO / 'scripts/testing/streaming/player.html', 'text/html; charset=utf-8'),
                         '/player.js': (REPO / 'scripts/testing/streaming/player.js', 'text/javascript'),
                         '/hls.min.js': (REPO / 'web-app/node_modules/hls.js/dist/hls.min.js', 'text/javascript')}
                path, content_type = files[self.path]
                if not path.is_file():
                    self.respond(503, {'error': 'fixture_asset_missing_run_npm_ci_in_web_app'})
                    return
                data = path.read_bytes()
                self.send_response(200)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Referrer-Policy', 'no-referrer')
                self.send_header('X-Frame-Options', 'DENY')
                self.end_headers()
                self.wfile.write(data)
            elif urlsplit(self.path).path == '/fixture/status':
                from media import request, safe_source
                if not self.fixture_host_allowed():
                    self.respond(403, {'error': 'fixture_requires_same_local_origin'})
                    return
                try:
                    access_source, session_id = self.acceptance_context()
                    if access_source == 'backend':
                        metadata = backend.metadata(session_id)
                        values = {'sessionId': session_id, 'path': 'live/' + session_id,
                                  'status': metadata.get('status')}
                    else:
                        values = json.loads((state / 'fixtures.json').read_text())
                except ValueError:
                    self.respond(400, {'error': 'invalid_session_context'})
                    return
                except BackendError as error:
                    self.backend_error(error, 'backend_metadata_failed')
                    return
                inspect_path = values['path'] if access_source == 'backend' else values.get('ingestPath', values['path'])
                status, source = request(state, 'GET', '/v3/paths/get/' + inspect_path)
                configured, _ = request(state, 'GET', '/v3/config/paths/get/' + inspect_path)
                event_path = values['path'] if access_source == 'backend' else values.get('ingestPath', values['path'])
                with event_lock:
                    events = ([json.loads(line) for line in events_file.read_text().splitlines()]
                              if events_file.exists() else [])
                self.respond(200, {'sessionId': values['sessionId'], 'path': values['path'],
                                  'accessSource': access_source, 'status': values.get('status'),
                                  'serverUrl': values.get('serverUrl'), 'playbackUrl': values.get('playbackUrl'),
                                  'backendEnabled': backend is not None,
                                  'configured': configured == 200,
                                  'source': safe_source(source) if status == 200 else None,
                                  'events': [event for event in events if event['path'] == event_path][-20:]})
            elif self.path.startswith('/fixture/backend-metadata'):
                self.backend_metadata()
            elif urlsplit(self.path).path == '/fixture/acceptance':
                from acceptance import evaluate
                if not self.fixture_host_allowed():
                    self.respond(403, {'error': 'fixture_requires_same_local_origin'})
                    return
                try:
                    access_source, session_id = self.acceptance_context()
                    self.respond(200, evaluate(state, session_id, access_source))
                except ValueError:
                    self.respond(400, {'error': 'invalid_session_context'})
                except BackendError as error:
                    self.backend_error(error, 'backend_acceptance_failed')
            else:
                self.respond(404, {'error': 'not_found'})

        def do_POST(self):
            if self.path not in [EVENTS_PATH, '/fixture/credentials', '/fixture/path', '/fixture/observation',
                                 '/fixture/backend-access']:
                self.respond(404, {'error': 'not_found'})
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
            except ValueError:
                self.respond(400, {'error': 'invalid_length'})
                return
            if not 0 < length <= MAX_BODY:
                self.respond(413, {'error': 'body_size'})
                return
            if self.headers.get_content_type() != 'application/json':
                self.respond(415, {'error': 'content_type'})
                return
            self.connection.settimeout(5)
            body = self.rfile.read(length)
            if self.path.startswith('/fixture/'):
                host = self.headers.get('Host', '')
                allowed = ['localhost:' + str(self.server.server_port), '127.0.0.1:' + str(self.server.server_port)]
                if host not in allowed or self.headers.get('Origin') != 'http://' + host:
                    self.respond(403, {'error': 'fixture_requires_same_local_origin'})
                    return
                try:
                    value = json.loads(body)
                    if self.path == '/fixture/backend-access':
                        if backend is None:
                            self.respond(503, {'error': 'backend_mode_disabled'})
                            return
                        if set(value) != {'sessionId'}:
                            raise ValueError('Invalid backend access request')
                        access = backend.acquire(value['sessionId'])
                        with event_lock:
                            backend_sessions.add(access.session_id)
                        self.respond(200, {'sessionId': access.session_id, 'playbackUrl': access.playback_url,
                                          'readToken': access.read_token, 'readExpiresAt': access.read_expires_at,
                                          'path': access.path, 'status': access.metadata.get('status')})
                        return
                    self.fixture_post(value)
                except (ValueError, TypeError, KeyError, AttributeError):
                    self.respond(400, {'error': 'invalid_fixture_request'})
                except BackendError as error:
                    self.backend_error(error, 'backend_access_failed')
                return
            try:
                event = json.loads(body)
                validate_event(event)
            except (ValueError, TypeError, KeyError, AttributeError):
                self.respond(400, {'error': 'invalid_event'})
                return
            with event_lock:
                duplicate = event['eventId'] in seen
                if not duplicate:
                    record = dict(event, receivedAt=datetime.now(timezone.utc).isoformat())
                    descriptor = os.open(events_file, os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
                    with os.fdopen(descriptor, 'a') as output:
                        output.write(json.dumps(record) + '\n')
                    seen.add(event['eventId'])
            self.respond(200, {'accepted': True, 'duplicate': duplicate})

        def fixture_post(self, value):
            from media import request
            backend_observation = (self.path == '/fixture/observation'
                                   and value.get('kind') in ['browser', 'obs']
                                   and value.get('accessSource') == 'backend')
            if backend_observation:
                session_id = str(UUID(value['sessionId']))
                with event_lock:
                    granted = session_id in backend_sessions
                if value['kind'] == 'browser' and not granted:
                    raise ValueError('Backend observation requires an access granted by this fixture')
                if backend is None:
                    raise ValueError('Backend mode disabled')
                metadata = backend.metadata(session_id)
                values = {'sessionId': session_id, 'path': 'live/' + session_id,
                          'backendStatus': metadata.get('status')}
            else:
                values = json.loads((state / 'fixtures.json').read_text())
            path = values['path']
            ingest_path = values.get('ingestPath', path)
            if self.path == '/fixture/path':
                if value == {'action': 'create'}:
                    if 'ingestPath' in values and 'relayReadToken' in values:
                        status, _ = request(state, 'GET', '/v3/config/paths/get/' + ingest_path)
                        if status == 404:
                            status, _ = request(state, 'POST', '/v3/config/paths/add/' + ingest_path,
                                                {'source': 'publisher', 'overridePublisher': False,
                                                 'runOnRead': '', 'runOnUnread': ''})
                        if status in [200, 404]:
                            playback_status, _ = request(state, 'GET', '/v3/config/paths/get/' + path)
                            if playback_status == 404:
                                source_url = 'rtmp://127.0.0.1:1935/' + ingest_path + '?token=' + values['relayReadToken']
                                playback_status, _ = request(state, 'POST', '/v3/config/paths/add/' + path,
                                                             {'source': source_url, 'sourceOnDemand': False,
                                                              'runOnOnline': '', 'runOnOffline': ''})
                            status = playback_status
                    else:
                        status, _ = request(state, 'GET', '/v3/config/paths/get/' + path)
                        if status == 404:
                            status, _ = request(state, 'POST', '/v3/config/paths/add/' + path,
                                                {'source': 'publisher', 'overridePublisher': False})
                elif value == {'action': 'delete'}:
                    status, _ = request(state, 'DELETE', '/v3/config/paths/delete/' + path)
                    if 'ingestPath' in values and status in [200, 404]:
                        status, _ = request(state, 'DELETE', '/v3/config/paths/delete/' + ingest_path)
                else:
                    raise ValueError('Invalid path action')
                self.respond(200 if status in [200, 404] else 503, {'ok': status in [200, 404]})
            elif self.path == '/fixture/credentials':
                if set(value) != {'kind', 'case'} or value['kind'] not in ['publish', 'read']:
                    raise ValueError('Invalid credential request')
                case = value['case']
                if case not in ['valid', 'missing', 'wrong-action', 'wrong-path', 'wrong-signature', 'expired']:
                    raise ValueError('Invalid credential case')
                check_path = ingest_path if value['kind'] == 'publish' else path
                configured, _ = request(state, 'GET', '/v3/config/paths/get/' + check_path)
                if configured != 200:
                    self.respond(409, {'error': 'create_fixture_path_first'})
                    return
                action = value['kind']
                if case == 'wrong-action':
                    action = 'read' if action == 'publish' else 'publish'
                if value['kind'] == 'publish':
                    token_path = ('ingest/' + str(uuid4())) if case == 'wrong-path' else ingest_path
                else:
                    token_path = ('live/' + str(uuid4())) if case == 'wrong-path' else path
                token_ttl = -1 if case == 'expired' else (None if action == 'publish' else READ_TTL)
                token = issue(state, action, token_path, token_ttl)
                if case == 'wrong-signature':
                    token = corrupt_signature(token)
                if case == 'missing':
                    token = None
                if value['kind'] == 'publish':
                    key_prefix = values.get('ingestKeyId', values['sessionId'])
                    self.respond(200, {'serverUrl': values['serverUrl'],
                                       'streamKey': key_prefix + ('?token=' + token if token else '')})
                else:
                    self.respond(200, {'playbackUrl': values['playbackUrl'], 'readToken': token, 'path': path})
            else:
                if value.get('kind') == 'browser':
                    expected = {'kind', 'case', 'frames', 'seconds', 'audioConfirmed', 'playlistLoads', 'segmentLoads', 'httpError'}
                    if 'accessSource' in value or 'sessionId' in value:
                        expected |= {'accessSource', 'sessionId'}
                        if value.get('accessSource') not in ['fixture', 'backend']:
                            raise ValueError('Invalid browser access source')
                        if str(UUID(value['sessionId'])) != values['sessionId']:
                            raise ValueError('Observation does not match the viewed session')
                    elif backend is not None:
                        raise ValueError('Browser observation requires its session and access source')
                    if set(value) != expected or value['case'] not in ['valid', 'missing', 'wrong-action', 'wrong-path', 'wrong-signature', 'expired']:
                        raise ValueError('Invalid browser result')
                    for field in ['frames', 'seconds', 'playlistLoads', 'segmentLoads', 'httpError']:
                        if type(value[field]) not in [int, float] or not 0 <= value[field] <= 1e9:
                            raise ValueError('Invalid measurement')
                    if type(value['audioConfirmed']) is not bool:
                        raise ValueError('Invalid audio confirmation')
                elif value.get('kind') == 'obs':
                    expected = {'kind', 'step'}
                    if 'accessSource' in value or 'sessionId' in value:
                        expected |= {'accessSource', 'sessionId'}
                        if (value.get('accessSource') not in ['fixture', 'backend']
                                or str(UUID(value['sessionId'])) != values['sessionId']):
                            raise ValueError('Invalid OBS session context')
                    elif backend is not None:
                        raise ValueError('OBS observation requires its session and access source')
                    if set(value) != expected or value['step'] not in ['publish', 'reconnect', 'old-key-rejected']:
                        raise ValueError('Invalid OBS result')
                else:
                    raise ValueError('Invalid observation')
                # Human observations are evidence for review, never an automatic task-completion switch.
                from media import safe_source
                inspect_path = path if (backend_observation or value.get('kind') == 'browser') else values.get('ingestPath', path)
                status, observed = request(state, 'GET', '/v3/paths/get/' + inspect_path)
                source = observed.get('source') if status == 200 else None
                configured, _ = request(state, 'GET', '/v3/config/paths/get/' + inspect_path)
                if configured not in [200, 404] or status not in [200, 404]:
                    self.respond(503, {'error': 'cannot_confirm_media_state'})
                    return
                if value['kind'] == 'obs':
                    active = value['step'] in ['publish', 'reconnect']
                    if (active and source is None) or (not active and (source is not None or configured != 404)):
                        self.respond(409, {'error': 'obs_observation_does_not_match_media_state'})
                        return
                record = dict(value, sessionId=values['sessionId'], source=source,
                              media=safe_source(observed) if status == 200 else None,
                              pathConfigured=configured == 200,
                              recordedAt=datetime.now(timezone.utc).isoformat())
                if backend_observation:
                    record['backendStatus'] = values['backendStatus']
                with event_lock:
                    descriptor = os.open(state / 'manual-results.jsonl', os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
                    with os.fdopen(descriptor, 'a') as output:
                        output.write(json.dumps(record) + '\n')
                self.respond(200, {'recorded': True})

    return ThreadingHTTPServer((host, port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'serve', 'verify'])
    parser.add_argument('--state-dir', type=Path, default=DEFAULT_STATE)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=18082)
    parser.add_argument('--gateway-base-url', default='http://localhost:8888')
    parser.add_argument('--account-cookie-file', type=Path)
    args = parser.parse_args()
    state = args.state_dir.resolve()
    backend = None
    if args.account_cookie_file:
        try:
            backend = BackendClient(args.gateway_base_url, load_cookie(args.account_cookie_file))
        except ValueError as error:
            parser.error(str(error))
    if args.command == 'prepare':
        prepare(state)
    elif args.command == 'verify':
        fixtures = json.loads((state / 'fixtures.json').read_text())
        for action in ['publish', 'read']:
            path = fixtures['ingestPath'] if action == 'publish' else fixtures['path']
            verify(state, fixtures[action + 'Token'], action, path)
        if set(jwks(state)['keys'][0]) != {'kty', 'kid', 'alg', 'use', 'n', 'e'}:
            raise ValueError('Unexpected JWKS parameters')
        print('PASS: publish/read signatures, claims and exact permissions; JWKS is public-only.')
    else:
        with make_server(state, args.host, args.port, backend=backend) as server:
            mode = 'backend account' if backend else 'local fixture tokens'
            browser_host = 'localhost' if args.host == '127.0.0.1' else args.host
            print('Fixture ready at http://' + browser_host + ':' + str(args.port) + ' (' + mode + ')', flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass


if __name__ == '__main__':
    main()
