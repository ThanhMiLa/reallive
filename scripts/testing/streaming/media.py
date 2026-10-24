#!/usr/bin/env python3
"""Control only the fixture session; never print media JWTs."""

import argparse
import json
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

import fixture

CONTROL = 'http://127.0.0.1:9997'


def request(state, method, endpoint, body=None, token=None):
    token = fixture.issue(state, 'api', '', 300) if token is None else token
    data = json.dumps(body).encode() if body is not None else None
    req = Request(CONTROL + endpoint, data=data, method=method,
                  headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with urlopen(req, timeout=5) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except HTTPError as error:
        with error:
            return error.code, None


def safe_source(value):
    return {key: value.get(key) for key in ['name', 'ready', 'online', 'source', 'tracks', 'readyTime', 'onlineTime'] if key in value}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['probe', 'create', 'info', 'delete'])
    parser.add_argument('--state-dir', type=fixture.Path, default=fixture.DEFAULT_STATE)
    args = parser.parse_args()
    state = args.state_dir.resolve()
    values = json.loads((state / 'fixtures.json').read_text())
    path = quote(values['path'], safe='/')
    if args.command == 'probe':
        status, _ = request(state, 'GET', '/v3/config/paths/list')
        assert status == 200, 'Control API did not accept api JWT: ' + str(status)
        for action in ['publish', 'read']:
            status, _ = request(state, 'GET', '/v3/config/paths/list',
                                token=fixture.issue(state, action, values['path'], 300))
            assert status in [401, 403], 'Control API accepted ' + action + ' JWT'
        print('PASS: Control API accepts api JWT and rejects publish/read JWTs')
    elif args.command == 'create':
        status, _ = request(state, 'GET', '/v3/config/paths/get/' + path)
        if status == 404:
            status, _ = request(state, 'POST', '/v3/config/paths/add/' + path,
                                {'source': 'publisher', 'overridePublisher': False})
        assert status == 200, 'Provision path failed: ' + str(status)
        print('Exact fixture path is configured: ' + values['path'])
    elif args.command == 'info':
        status, value = request(state, 'GET', '/v3/paths/get/' + path)
        print(json.dumps({'status': status, 'path': safe_source(value) if value else None}))
    else:
        status, _ = request(state, 'DELETE', '/v3/config/paths/delete/' + path)
        assert status in [200, 404], 'Delete path failed: ' + str(status)
        status, _ = request(state, 'GET', '/v3/config/paths/get/' + path)
        assert status == 404, 'Path configuration still exists'
        status, value = request(state, 'GET', '/v3/paths/get/' + path)
        if status == 200 and value.get('source'):
            source = value['source']
            protocol = {'rtmpConn': 'rtmp', 'rtmpsConn': 'rtmps'}[source['type']]
            status, _ = request(state, 'POST', '/v3/' + protocol + '/conns/kick/' + source['id'])
            assert status in [200, 404], 'Kick source failed'
        print('Fixture path configuration deleted; old-token rejection still needs a publish attempt.')


if __name__ == '__main__':
    main()
