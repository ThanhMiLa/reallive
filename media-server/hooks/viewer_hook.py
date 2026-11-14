#!/usr/bin/env python3
"""Enrich HLS reader events and send bounded internal viewer callbacks."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from uuid import UUID, uuid4


REQUEST_TIMEOUT = 2
TOTAL_TIMEOUT = 10
MAX_ATTEMPTS = 3
MAX_RESPONSE_BYTES = 16384


class HookFailure(Exception):
    """Represent a failure whose message is safe to write to logs."""


class HookCancelled(Exception):
    """Stop a hook when MediaMTX closes its reader or shuts down."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def identifier(value):
    if not isinstance(value, str):
        raise HookFailure('invalid-reader-metadata')
    try:
        return str(UUID(value))
    except ValueError:
        raise HookFailure('invalid-reader-metadata') from None


def endpoint(value):
    try:
        parsed = urlsplit(value)
        if (parsed.scheme not in ['http', 'https'] or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.query or parsed.fragment):
            raise ValueError
        parsed.port
    except (TypeError, ValueError):
        raise HookFailure('invalid-http-configuration') from None
    return value.rstrip('/')


def secret(value):
    if not value or len(value) > MAX_RESPONSE_BYTES or any(char.isspace() for char in value):
        raise HookFailure('invalid-credential-configuration')
    return value


class ViewerHook:
    def __init__(self, environment):
        self.environment = environment
        self.deadline = time.monotonic() + TOTAL_TIMEOUT
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def remaining(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise HookFailure('deadline-exceeded')
        return remaining

    def control_token(self):
        token_file = self.environment.get('MEDIA_CONTROL_TOKEN_FILE', '/run/media-hook/control.jwt')
        try:
            with Path(token_file).open(encoding='utf-8') as source:
                token = source.read(MAX_RESPONSE_BYTES + 1)
        except (OSError, UnicodeError, ValueError):
            raise HookFailure('control-credential-unavailable') from None
        return secret(token.strip())

    def request(self, method, url, body=None):
        control = method == 'GET'
        reloaded_after_unauthorized = False
        for attempt in range(MAX_ATTEMPTS):
            timeout = min(REQUEST_TIMEOUT, self.remaining())
            headers = {'Accept': 'application/json'}
            if control:
                headers['Authorization'] = 'Bearer ' + self.control_token()
            else:
                headers['Content-Type'] = 'application/json'
            request = Request(url, data=body, headers=headers, method=method)
            try:
                with self.opener.open(request, timeout=timeout) as response:
                    if not control:
                        return None
                    payload = response.read(MAX_RESPONSE_BYTES + 1)
                    if len(payload) > MAX_RESPONSE_BYTES:
                        raise HookFailure('invalid-control-response')
                    try:
                        value = json.loads(payload)
                    except (ValueError, UnicodeError):
                        raise HookFailure('invalid-control-response') from None
                    if not isinstance(value, dict):
                        raise HookFailure('invalid-control-response')
                    return value
            except HTTPError as error:
                status = error.code
                error.close()
                if control and status == 404:
                    return None
                if control and status == 401 and not reloaded_after_unauthorized:
                    reloaded_after_unauthorized = True
                elif status == 503 or (not control and status == 409):
                    pass
                else:
                    raise HookFailure(('control' if control else 'callback') + '-rejected-' + str(status)) from None
            except (URLError, TimeoutError, OSError):
                pass
            if attempt + 1 < MAX_ATTEMPTS:
                time.sleep(min(0.2 * (attempt + 1), self.remaining()))
        raise HookFailure(('control' if control else 'callback') + '-unavailable')

    def run(self, action):
        reader_type = self.environment['MTX_READER_TYPE']
        if reader_type != 'hlsSession':
            return
        path = self.environment['MTX_PATH']
        if not path.startswith('live/'):
            raise HookFailure('invalid-reader-metadata')
        session_id = identifier(path[5:])
        reader_id = identifier(self.environment['MTX_READER_ID'])
        callback_url = endpoint(self.environment.get(
            'STREAM_VIEWER_CALLBACK_URL',
            'http://host.docker.internal:8082/stream/internal/api/viewer-events'))
        event = {
            'eventId': str(uuid4()),
            'eventType': action.upper(),
            'sessionId': session_id,
            'readerId': reader_id,
            'readerType': 'hlsSession',
            'occurredAt': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'),
        }
        if action == 'read':
            control_base = endpoint(self.environment.get('MEDIA_CONTROL_BASE_URL', 'http://127.0.0.1:9997'))
            value = self.request('GET', control_base + '/v3/hls/sessions/get/' + reader_id)
            if value is None:
                return
            if identifier(value.get('id')) != reader_id or value.get('path') != path:
                raise HookFailure('control-reader-mismatch')
            user = value.get('user')
            if not isinstance(user, str) or not user:
                raise HookFailure('invalid-control-response')
            try:
                event['userId'] = str(UUID(user))
            except ValueError:
                # Technical readers such as media-worker are not viewer accounts.
                return
        body = json.dumps(event, separators=(',', ':')).encode()
        self.request('POST', callback_url, body=body)


def cancelled(_signal, _frame):
    raise HookCancelled


def deadline_exceeded(_signal, _frame):
    raise HookFailure('deadline-exceeded')


def main():
    if os.environ.get('STREAM_CCU_ENABLED', 'false').strip().lower() != 'true':
        return 0
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ['read', 'unread']:
            raise HookFailure('invalid-action')
        signal.signal(signal.SIGINT, cancelled)
        signal.signal(signal.SIGTERM, cancelled)
        if hasattr(signal, 'setitimer'):
            signal.signal(signal.SIGALRM, deadline_exceeded)
            signal.setitimer(signal.ITIMER_REAL, TOTAL_TIMEOUT)
        ViewerHook(os.environ).run(sys.argv[1])
        return 0
    except HookCancelled:
        return 0
    except (KeyError, HookFailure) as error:
        reason = str(error) if isinstance(error, HookFailure) else 'missing-reader-configuration'
        print('Viewer hook failed; reason=' + reason, file=sys.stderr)
        return 1
    except Exception:
        print('Viewer hook failed; reason=unexpected-error', file=sys.stderr)
        return 1
    finally:
        if hasattr(signal, 'setitimer'):
            signal.setitimer(signal.ITIMER_REAL, 0)


if __name__ == '__main__':
    sys.exit(main())
