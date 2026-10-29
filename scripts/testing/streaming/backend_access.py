#!/usr/bin/env python3
"""Call the real Stream Service playback APIs for the local player fixture.

The account cookie is read from a private file and only forwarded to the configured
Gateway. Tokens are kept in memory and are never printed or written to reports.
"""

import argparse
from dataclasses import dataclass, field
import json
from pathlib import Path
import re
import stat
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID

DEFAULT_GATEWAY = 'http://localhost:8888'
MAX_COOKIE_BYTES = 8192
SESSION_PATH = '/stream/api/streams/'


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class BackendError(RuntimeError):
    """A safe, credential-free playback API failure."""

    def __init__(self, message, status=None, code=None):
        super().__init__(message)
        self.status = status
        self.code = code


@dataclass(frozen=True)
class BackendAccess:
    session_id: str
    playback_url: str
    read_expires_at: str
    path: str
    metadata: dict = field(repr=False)
    read_token: str = field(repr=False)

    def __repr__(self):
        return (f'BackendAccess(sessionId={self.session_id}, playbackUrl={self.playback_url}, '
                f'readExpiresAt={self.read_expires_at}, credentials=REDACTED)')


def load_cookie(path):
    cookie_file = Path(path)
    if cookie_file.is_symlink():
        raise ValueError('Account cookie file must not be a symbolic link')
    try:
        mode = cookie_file.stat().st_mode
    except FileNotFoundError:
        raise ValueError('Account cookie file does not exist') from None
    if mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise ValueError('Account cookie file must be private; run chmod 600 on the file')
    raw = cookie_file.read_bytes()
    if not raw or len(raw) > MAX_COOKIE_BYTES:
        raise ValueError('Account cookie file is empty or too large')
    value = raw.decode('utf-8').strip()
    if not value or '\r' in value or '\n' in value:
        raise ValueError('Account cookie file must contain one Cookie header line')
    if re.search(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', value):
        raise ValueError('Account cookie file contains control characters')
    return value


def safe_error_detail(value):
    if not isinstance(value, dict):
        return None, None
    code = value.get('code') if isinstance(value.get('code'), int) else None
    message = value.get('message') if isinstance(value.get('message'), str) else None
    return code, message[:200] if message else None


class BackendClient:
    def __init__(self, gateway_base_url, account_cookie, timeout=10):
        parsed = urlsplit(gateway_base_url)
        if parsed.scheme not in ['http', 'https'] or not parsed.netloc or parsed.query or parsed.fragment:
            raise ValueError('Gateway base URL must be an HTTP(S) origin')
        self.gateway_base_url = gateway_base_url.rstrip('/')
        self.account_cookie = account_cookie
        self.timeout = timeout
        self.opener = build_opener(NoRedirect())

    def metadata(self, session_id):
        session_id = str(UUID(str(session_id)))
        payload = self._request('GET', SESSION_PATH + session_id)
        return self._result(payload, session_id)

    def acquire(self, session_id):
        session_id = str(UUID(str(session_id)))
        metadata = self.metadata(session_id)
        if metadata.get('status') != 'LIVE':
            raise BackendError('Session is not LIVE', status=409, code='STREAM_SESSION_STATE_INVALID')
        payload = self._request('POST', SESSION_PATH + session_id + '/playback-access')
        access = self._result(payload, session_id)
        required = {'sessionId', 'playbackUrl', 'readToken', 'readExpiresAt'}
        if not required.issubset(access) or any(not isinstance(access[field], str) or not access[field]
                                                for field in required):
            raise BackendError('Playback response is missing required fields', status=502)
        if access['sessionId'] != session_id:
            raise BackendError('Playback response belongs to another session', status=502)
        parsed = urlsplit(access['playbackUrl'])
        path = 'live/' + session_id
        if parsed.scheme not in ['http', 'https'] or not parsed.netloc or parsed.username or parsed.password:
            raise BackendError('Playback URL is not a safe public HTTP(S) URL', status=502)
        if not parsed.path.startswith('/' + path + '/'):
            raise BackendError('Playback URL does not match the session path', status=502)
        return BackendAccess(session_id, access['playbackUrl'], access['readExpiresAt'], path,
                             metadata, access['readToken'])

    def _request(self, method, path):
        request = Request(self.gateway_base_url + path, method=method,
                          headers={'Accept': 'application/json', 'Cookie': self.account_cookie})
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                body = response.read()
        except HTTPError as error:
            with error:
                if 300 <= error.code < 400:
                    raise BackendError('Gateway redirects are not allowed', status=502) from None
                try:
                    code, message = safe_error_detail(json.loads(error.read()))
                except (ValueError, TypeError):
                    code, message = None, None
            raise BackendError(message or 'Playback API request failed', error.code, code) from None
        except URLError:
            raise BackendError('Gateway is unavailable') from None
        try:
            return json.loads(body)
        except (ValueError, TypeError):
            raise BackendError('Gateway returned an invalid response', status=502) from None

    @staticmethod
    def _result(payload, session_id):
        if not isinstance(payload, dict) or payload.get('code') != 1000 or not isinstance(payload.get('result'), dict):
            raise BackendError('Gateway returned an unexpected envelope', status=502)
        result = payload['result']
        if result.get('id') is not None and result.get('id') != session_id:
            raise BackendError('Gateway returned another session', status=502)
        return result


def public_summary(access):
    parsed = urlsplit(access.playback_url)
    return {'sessionId': access.session_id, 'status': access.metadata.get('status'),
            'mediaOrigin': parsed.scheme + '://' + parsed.netloc, 'mediaPath': parsed.path,
            'readExpiresAt': access.read_expires_at}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session-id', required=True, type=UUID)
    parser.add_argument('--account-cookie-file', required=True, type=Path)
    parser.add_argument('--gateway-base-url', default=DEFAULT_GATEWAY)
    args = parser.parse_args()
    try:
        client = BackendClient(args.gateway_base_url, load_cookie(args.account_cookie_file))
        access = client.acquire(args.session_id)
        print('PASS: backend metadata and playback-access returned a Read JWT for a LIVE session.')
        print('Safe summary:', json.dumps(public_summary(access), ensure_ascii=False))
    except (BackendError, ValueError) as error:
        print('FAIL:', error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
