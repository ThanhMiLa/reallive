#!/usr/bin/env python3
"""Exercise persistent-key owner setup, current session, and key reset APIs without leaking credentials."""

import argparse
import json
import os
from pathlib import Path
import re
import tempfile
import time
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


def request(base_url, cookie, method, path, body, expected, extra_headers=None):
    headers = {'Cookie': 'access_token=' + cookie, 'Accept': 'application/json'}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    if extra_headers:
        headers.update(extra_headers)
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
        raise RuntimeError(method + ' ' + path + ' failed') from None
    if status not in expected or result.get('status') != status or result.get('code') != 1000:
        raise RuntimeError('Unexpected API response for ' + method + ' ' + path)
    return result.get('result'), cache_control


def await_setup_ready(base_url, cookie, timeout_seconds=20):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        setup, cache_control = request(base_url, cookie, 'GET', '/api/streams/me/setup', None, {200})
        if cache_control != 'no-store':
            raise RuntimeError('Setup response must use Cache-Control: no-store')
        if setup.get('status') == 'ACTIVE' and setup.get('publishReady') and setup.get('streamKey'):
            return setup
        time.sleep(0.25)
    raise RuntimeError('Stream setup did not become ACTIVE and publishReady')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8888/stream')
    parser.add_argument('--cookie-file', required=True, type=Path, help='Private file containing access_token or its raw JWT')
    parser.add_argument('--category-id', required=True, type=UUID)
    parser.add_argument('--title', default='Persistent stream key smoke')
    parser.add_argument('--update-title', default='Persistent stream key smoke updated')
    parser.add_argument('--credentials-file', type=Path, default=REPOSITORY / '.local/streaming/persistent-obs-credentials.json')
    args = parser.parse_args()
    base_url = args.base_url.rstrip('/')
    parsed = urlsplit(base_url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Invalid backend base URL')
    cookie = args.cookie_file.read_text(encoding='utf-8').strip().removeprefix('access_token=')
    if not re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', cookie):
        raise ValueError('Cookie file must contain only the access token')

    draft, draft_cc = request(base_url, cookie, 'GET', '/api/streams/me/setup', None, {200})
    if draft_cc != 'no-store':
        raise RuntimeError('Setup response must use Cache-Control: no-store')

    saved, save_cc = request(base_url, cookie, 'PUT', '/api/streams/me/setup',
                             {'title': args.title, 'categoryId': str(args.category_id)}, {200})
    if save_cc != 'no-store':
        raise RuntimeError('Save setup response must use Cache-Control: no-store')

    setup = await_setup_ready(base_url, cookie)
    if '?token=' not in setup['streamKey']:
        raise RuntimeError('Invalid persistent streamKey format')
    ingest_key_id = str(UUID(setup['streamKey'].split('?token=', 1)[0]))

    current, _ = request(base_url, cookie, 'GET', '/api/streams/me/current', None, {200})
    if current is not None and any(field in json.dumps(current) for field in ('streamKey', 'token=')):
        raise RuntimeError('Current session response leaked a credential')

    updated, _ = request(base_url, cookie, 'PUT', '/api/streams/me/setup',
                           {'title': args.update_title, 'categoryId': str(args.category_id)}, {200})
    if updated['title'] != args.update_title or updated['streamKey'] != setup['streamKey']:
        raise RuntimeError('Updating metadata unexpectedly changed persistent stream key')

    reset_op, reset_cc = request(base_url, cookie, 'POST', '/api/streams/me/stream-key/reset', None, {202},
                                 extra_headers={'Idempotency-Key': str(uuid4())})
    if reset_cc != 'no-store':
        raise RuntimeError('Reset response must use Cache-Control: no-store')
    op_id = str(UUID(reset_op['operationId']))

    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        op, _ = request(base_url, cookie, 'GET', '/api/streams/me/operations/' + op_id, None, {200})
        if op.get('status') == 'SUCCEEDED':
            break
        if op.get('status') == 'CANCELLED':
            raise RuntimeError('Reset operation was cancelled')
        time.sleep(0.25)
    else:
        raise RuntimeError('Reset operation did not finish in time')

    rotated = await_setup_ready(base_url, cookie)
    if rotated['streamKey'] == setup['streamKey']:
        raise RuntimeError('Stream key reset did not issue a new persistent key')

    write_private(args.credentials_file, {
        'channelId': rotated['channelId'],
        'generation': rotated['generation'],
        'serverUrl': rotated['serverUrl'],
        'streamKey': rotated['streamKey'],
    })
    print('PASS: setup/save/current/reset/operations verified; channelId=' + str(rotated['channelId'])
          + ' prevIngestKey=' + ingest_key_id + ' generation=' + str(rotated['generation']))
    print('OBS credentials saved privately to ' + str(args.credentials_file))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError, TypeError) as error:
        if isinstance(error, RuntimeError):
            print('FAIL: ' + str(error))
        else:
            print('FAIL: invalid input or local state; check base URL, category, and cookie format')
        raise SystemExit(1)
