#!/usr/bin/env python3
"""Exercise phase 3 owner APIs without printing account or media credentials."""

import argparse
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID, uuid4


REPOSITORY = Path(__file__).resolve().parents[3]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def write_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    name = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False, encoding='utf-8') as output:
            name = output.name
            os.chmod(name, 0o600)
            json.dump(value, output, indent=2)
            output.write('\n')
        os.replace(name, path)
    finally:
        if name and Path(name).exists():
            Path(name).unlink()


def request(base_url, cookie, method, path, body, expected):
    headers = {'Cookie': 'access_token=' + cookie, 'Accept': 'application/json'}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    operation = Request(base_url + path, data=None if body is None else json.dumps(body).encode(),
                        headers=headers, method=method)
    try:
        with build_opener(NoRedirect()).open(operation, timeout=20) as response:
            status = response.status
            result = json.load(response)
            cache_control = response.headers.get('Cache-Control', '')
    except HTTPError as error:
        status = error.code
        error.close()
        raise RuntimeError(method + ' ' + path + ' returned HTTP ' + str(status)) from None
    except (URLError, TimeoutError, OSError, ValueError):
        raise RuntimeError(method + ' ' + path + ' failed; keep the same state file when retrying') from None
    if status not in expected or result.get('status') != status or result.get('code') != 1000:
        raise RuntimeError('Unexpected API response for ' + method + ' ' + path)
    return result.get('result'), cache_control


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8888/stream')
    parser.add_argument('--cookie-file', required=True, type=Path, help='Private file containing access_token or its raw JWT')
    parser.add_argument('--category-id', required=True, type=UUID)
    parser.add_argument('--title', default='Streaming phase 3 smoke')
    parser.add_argument('--update-title', default='Streaming phase 3 smoke updated')
    parser.add_argument('--state-file', type=Path, default=REPOSITORY / '.local/streaming/phase3-smoke.json')
    parser.add_argument('--credentials-file', type=Path, default=REPOSITORY / '.local/streaming/phase3-obs-credentials.json')
    args = parser.parse_args()
    base_url = args.base_url.rstrip('/')
    parsed = urlsplit(base_url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Invalid backend base URL')
    cookie = args.cookie_file.read_text(encoding='utf-8').strip().removeprefix('access_token=')
    if not re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', cookie):
        raise ValueError('Cookie file must contain only the access token')
    if args.state_file.exists():
        state = json.loads(args.state_file.read_text(encoding='utf-8'))
        UUID(state['requestId'])
        if state.get('baseUrl') != base_url or state.get('categoryId') != str(args.category_id):
            raise ValueError('Existing state belongs to another backend/category; use a different state file for a new test')
    else:
        state = {'requestId': str(uuid4()), 'baseUrl': base_url, 'title': args.title, 'categoryId': str(args.category_id)}
        write_private(args.state_file, state)
    body = {key: state[key] for key in ('requestId', 'title', 'categoryId')}
    created, cache_control = request(base_url, cookie, 'POST', '/api/streams', body, {200, 201})
    if cache_control != 'no-store':
        raise RuntimeError('Create credential response must use Cache-Control: no-store')
    session_id = str(UUID(created['session']['id']))
    state['sessionId'] = session_id
    write_private(args.state_file, state)
    replay, _ = request(base_url, cookie, 'POST', '/api/streams', body, {200})
    if replay['session']['id'] != session_id:
        raise RuntimeError('Idempotent replay returned another session')
    current, _ = request(base_url, cookie, 'GET', '/api/streams/me/current', None, {200})
    if not current or current['id'] != session_id or not current['publishReady']:
        raise RuntimeError('Current session is absent or not ready to publish')
    if any(field in json.dumps(current) for field in ('streamKey', 'publishCredentials', 'token=')):
        raise RuntimeError('Metadata leaked a credential')
    updated, _ = request(base_url, cookie, 'PATCH', '/api/streams/' + session_id, {'title': args.update_title}, {200})
    if updated['title'] != args.update_title or updated['categoryId'] != str(args.category_id):
        raise RuntimeError('Partial metadata update did not preserve the category')
    credential, cache_control = request(base_url, cookie, 'POST', '/api/streams/' + session_id + '/publish-credentials', None, {200})
    if cache_control != 'no-store' or credential['sessionId'] != session_id or not credential['streamKey'].startswith(session_id + '?token='):
        raise RuntimeError('Invalid publish credential contract')
    write_private(args.credentials_file, credential)
    print('PASS: Create/replay/current/PATCH/publish-credentials; sessionId=' + session_id)
    print('OBS credentials saved privately to ' + str(args.credentials_file))
    print('Keep the state file for retries. READY does not become LIVE until phase 4 lifecycle handlers exist.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError, TypeError) as error:
        # Only our sanitized errors are printed; never dump HTTP bodies, cookie headers or JWTs.
        if isinstance(error, RuntimeError):
            print('FAIL: ' + str(error))
        else:
            print('FAIL: invalid input or local state; check paths, category, cookie format and state file')
        raise SystemExit(1)
