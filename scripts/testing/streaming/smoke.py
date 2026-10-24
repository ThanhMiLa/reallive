#!/usr/bin/env python3
"""Protocol checks using synthetic media. OBS/browser acceptance remains separate."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import quote, urljoin, urlsplit
from urllib.request import Request, urlopen
from uuid import uuid4

import fixture
import media


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def wait_for(predicate, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.15)
    raise RuntimeError('Timed out waiting for media condition')


def publisher(path, token, duration=90):
    url = 'rtmp://127.0.0.1:1935/' + path
    if token is not None:
        url += '?token=' + token
    # Capture and discard stderr: FFmpeg errors can contain its full output URL.
    args = ['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-re',
            '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=15',
            '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100',
            '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-g', '30',
            '-c:a', 'aac', '-b:a', '64k', '-t', str(duration), '-f', 'flv', url]
    return subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def stop(process):
    if process is None:
        return
    if process.poll() is None:
        process.terminate()
    try:
        process.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()


def get(url, token=None, method='GET', headers=None):
    request_headers = dict(headers or {})
    if token is not None:
        request_headers['Authorization'] = 'Bearer ' + token
    request = Request(url, headers=request_headers, method=method)
    try:
        with urlopen(request, timeout=8) as response:
            return response.status, response.read(), dict(response.headers), response.geturl()
    except HTTPError as error:
        with error:
            return error.code, b'', dict(error.headers), url


def safe_media_url(base, reference, path):
    url = urljoin(base, reference)
    parsed = urlsplit(url)
    require(parsed.scheme == 'http' and parsed.netloc == 'localhost:8898'
            and parsed.path.startswith('/' + path + '/'), 'Unexpected manifest URL origin/path')
    return url


def run(state):
    require(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'FFmpeg/ffprobe are required for protocol checks')
    path = 'live/' + str(uuid4())
    escaped = quote(path, safe='/')
    publish = fixture.issue(state, 'publish', path, 86400)
    read = fixture.issue(state, 'read', path, 900)
    playback = 'http://localhost:8898/' + path + '/index.m3u8'
    checks = []
    process = None
    created = False

    def passed(name, **details):
        checks.append(dict(name=name, passed=True, **details))
        print('PASS: ' + name, flush=True)

    def source():
        status, value = media.request(state, 'GET', '/v3/paths/get/' + escaped)
        return value if status == 200 and value.get('online') and value.get('source') else None

    def callback(kind, source_id):
        events = state / 'events.jsonl'
        if events.exists():
            for line in events.read_text().splitlines():
                event = json.loads(line)
                if event['path'] == path and event['eventType'] == kind and event['sourceId'] == source_id:
                    return event
        return None

    def rejected(token, output_path=path):
        # MediaMTX delays auth failures; a 2s output can finish before FFmpeg notices rejection.
        candidate = publisher(output_path, token, duration=8)
        try:
            candidate.communicate(timeout=12)
            require(candidate.returncode != 0, 'Unexpected publish acceptance')
        except subprocess.TimeoutExpired:
            raise RuntimeError('Rejected publish did not disconnect') from None
        finally:
            stop(candidate)

    try:
        status, _ = media.request(state, 'GET', '/v3/config/paths/list')
        require(status == 200, 'Control API api token failed')
        for action in ['publish', 'read']:
            status, _ = media.request(state, 'GET', '/v3/config/paths/list', token=fixture.issue(state, action, path, 300))
            require(status in [401, 403], 'Control API accepted client token')
        passed('control-api-permission-separation')

        status, _ = media.request(state, 'POST', '/v3/config/paths/add/' + escaped,
                                  {'source': 'publisher', 'overridePublisher': False})
        require(status == 200, 'Exact path creation failed')
        created = True
        status, value = media.request(state, 'GET', '/v3/paths/get/' + escaped)
        require(status == 200 and not value['online'] and value['source'] is None,
                'Configured path incorrectly reports online without media')
        passed('provisioning-alone-is-not-online')

        process = publisher(path, publish)
        first = wait_for(source)
        first_id = first['source']['id']
        event = wait_for(lambda: callback('ONLINE', first_id))
        require(event['sourceType'] == 'rtmpConn', 'Unexpected hook source type')
        require(any('H264' in track for track in first['tracks']), 'H264 track missing')
        require(len(first['tracks']) == 2, 'Expected audio and video tracks')
        passed('publish-h264-aac-and-online-hook', sourceId=first_id, sourceType=event['sourceType'], tracks=first['tracks'])

        status, _ = media.request(state, 'GET', '/v3/config/paths/get/' + escaped)
        require(status == 200 and source()['source']['id'] == first_id, 'Path inspection changed live source')
        rejected(publish)
        require(source()['source']['id'] == first_id, 'Second publisher replaced original source')
        passed('second-publisher-cannot-replace-live-source')

        status, _, headers, _ = get(playback, method='OPTIONS', headers={
            'Origin': 'http://localhost:18082', 'Access-Control-Request-Method': 'GET',
            'Access-Control-Request-Headers': 'authorization'})
        require(status == 204 and headers.get('Access-Control-Allow-Origin') == 'http://localhost:18082'
                and 'authorization' in headers.get('Access-Control-Allow-Headers', '').lower(), 'HLS CORS preflight failed')
        status, _, headers, _ = get(playback, method='OPTIONS', headers={
            'Origin': 'http://untrusted.invalid', 'Access-Control-Request-Method': 'GET',
            'Access-Control-Request-Headers': 'authorization'})
        require(headers.get('Access-Control-Allow-Origin') not in ['*', 'http://untrusted.invalid'], 'Untrusted CORS origin allowed')
        passed('cors-authorization-preflight-and-origin-allowlist')

        def master_ready():
            result = get(playback, read)
            return result if result[0] == 200 else None

        _, manifest, _, master_url = wait_for(master_ready, timeout=20)
        require(manifest.startswith(b'#EXTM3U'), 'Invalid HLS manifest')
        variants = [line for line in manifest.decode().splitlines() if line and not line.startswith('#')]
        # fMP4 HLS can put audio in its own rendition playlist, separate from video.
        variants += re.findall(r'#EXT-X-MEDIA:[^\n]*URI="([^"]+)"', manifest.decode())
        require(bool(variants), 'No HLS variant')
        codecs = set()
        for variant in dict.fromkeys(variants):
            playlist_url = safe_media_url(master_url, variant, path)

            def playlist_ready():
                result = get(playlist_url, read)
                return result if result[0] == 200 and b'#EXTINF:' in result[1] else None

            _, playlist, _, playlist_url = wait_for(playlist_ready, timeout=20)
            text = playlist.decode()
            initialization = re.search(r'#EXT-X-MAP:URI="([^"]+)"', text)
            segments = [line for line in text.splitlines() if line and not line.startswith('#')]
            require(initialization is not None and bool(segments), 'fMP4 init/segment missing')
            init_url = safe_media_url(playlist_url, initialization.group(1), path)
            segment_url = safe_media_url(playlist_url, segments[-1], path)
            init_status, init, _, _ = get(init_url, read)
            segment_status, segment, _, _ = get(segment_url, read)
            require(init_status == segment_status == 200 and init and segment, 'Cannot fetch HLS media')
            with tempfile.TemporaryDirectory() as directory:
                fragment = Path(directory) / 'fragment.mp4'
                fragment.write_bytes(init + segment)
                probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'stream=codec_name,codec_type',
                                        '-of', 'json', str(fragment)], capture_output=True)
                require(probe.returncode == 0, 'HLS fragment probe failed')
                codecs.update(stream['codec_name'] for stream in json.loads(probe.stdout)['streams'])
                decoded = subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-i', str(fragment), '-f', 'null', '-'], capture_output=True)
                require(decoded.returncode == 0, 'HLS audio/video decode failed')
        require({'h264', 'aac'} <= codecs, 'HLS codecs do not include H264/AAC')
        passed('authenticated-hls-playlist-init-segment-and-av-decode', codecs=sorted(codecs),
               segmentExtension=Path(urlsplit(segment_url).path).suffix)

        read_cases = {'missing': None, 'wrong-action': publish,
                      'wrong-path': fixture.issue(state, 'read', 'live/' + str(uuid4()), 900),
                      'wrong-signature': fixture.corrupt_signature(read),
                      'expired': fixture.issue(state, 'read', path, -1)}
        for name, token in read_cases.items():
            status, _, _, _ = get(playback, token)
            require(status in [401, 403], 'Invalid Read JWT accepted for new HLS session: ' + name)
        passed('new-hls-session-rejects-missing-action-path-signature-expiry')

        short_read = fixture.issue(state, 'read', path, 4)
        status, short_master, _, short_master_url = get(playback, short_read)
        require(status == 200, 'Short-lived Read JWT did not open an HLS session')
        short_variant = next(line for line in short_master.decode().splitlines() if line and not line.startswith('#'))
        short_playlist = safe_media_url(short_master_url, short_variant, path)
        expiration = json.loads(fixture.unb64url(short_read.split('.')[1]))['exp']
        time.sleep(max(0, expiration - time.time() + 0.1))
        status, _, _, _ = get(short_playlist, short_read)
        require(status == 200, 'Existing HLS session unexpectedly closed at JWT expiry')
        status, _, _, _ = get(playback, short_read)
        require(status in [401, 403], 'Expired original Read JWT opened a new HLS session')
        passed('existing-hls-session-not-revoked-by-expired-jwt')

        stop(process)
        process = None
        wait_for(lambda: not source())
        wait_for(lambda: callback('OFFLINE', first_id))
        passed('offline-hook-matches-original-source')

        for name, token in {'missing': None, 'read-action': read,
                            'wrong-signature': fixture.corrupt_signature(publish),
                            'wrong-path': fixture.issue(state, 'publish', 'live/' + str(uuid4()), 900),
                            'expired': fixture.issue(state, 'publish', path, -1)}.items():
            rejected(token)
            require(not source(), 'Invalid Publish JWT created source: ' + name)
        unknown_path = 'live/' + str(uuid4())
        rejected(fixture.issue(state, 'publish', unknown_path, 900), unknown_path)
        passed('publish-rejects-missing-action-path-signature-expiry-and-unregistered-path')

        process = publisher(path, publish)
        second = wait_for(source)
        second_id = second['source']['id']
        require(second_id != first_id, 'Reconnect reused connection ID')
        wait_for(lambda: callback('ONLINE', second_id))
        passed('reconnect-same-jwt-and-path-new-source', sourceId=second_id)

        status, _ = media.request(state, 'DELETE', '/v3/config/paths/delete/' + escaped)
        require(status == 200, 'Path delete while publishing failed')
        status, _ = media.request(state, 'GET', '/v3/config/paths/get/' + escaped)
        require(status == 404, 'Deleted path configuration still exists')
        wait_for(lambda: not source())
        wait_for(lambda: callback('OFFLINE', second_id))
        stop(process)
        process = None
        fixture.verify(state, publish, 'publish', path)
        rejected(publish)
        require(not source(), 'Old valid JWT reopened deleted path')
        passed('delete-live-path-stops-source-and-rejects-still-valid-old-jwt')
        return checks
    finally:
        stop(process)
        if created:
            status, _ = media.request(state, 'DELETE', '/v3/config/paths/delete/' + escaped)
            require(status in [200, 404], 'Fixture smoke path cleanup failed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', type=Path, default=fixture.DEFAULT_STATE)
    args = parser.parse_args()
    state = args.state_dir.resolve()
    report_path = state / 'smoke-results.json'
    fixture.write_private_json(report_path, {'status': 'RUNNING', 'checks': []})
    try:
        checks = run(state)
    except Exception as error:
        # Never print subprocess argv/HTTP bodies containing tokens.
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        fixture.write_private_json(report_path, {'status': 'FAIL', 'checks': [], 'failure': message})
        print('FAIL: ' + message, flush=True)
        return 1
    fixture.write_private_json(report_path, {
        'status': 'PASS',
        'testedAt': datetime.now(timezone.utc).isoformat(), 'mediaVersion': '1.21.2',
        'publisher': 'FFmpeg synthetic H264/AAC', 'checks': checks,
        'manualAcceptanceRequired': ['OBS publishing/reconnect', 'Chrome/Edge hls.js picture/audio and Network']})
    print('Saved safe protocol results; OBS/browser manual acceptance is still required.', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
