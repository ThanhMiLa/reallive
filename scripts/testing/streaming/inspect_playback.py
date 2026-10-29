#!/usr/bin/env python3
"""Inspect an existing local session's HLS using dev keys, without changing its path or publisher."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, urlopen
from uuid import UUID

import fixture
import media
from backend_access import BackendClient, BackendError, load_cookie


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def fetch(url, token=None):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    try:
        with urlopen(Request(url, headers=headers), timeout=30) as response:
            return response.status, response.read(), response.geturl()
    except HTTPError as error:
        with error:
            return error.code, b'', url


def media_url(base, reference, path, media_origin):
    result = urljoin(base, reference)
    parsed = urlsplit(result)
    require(parsed.scheme == 'http' and parsed.netloc == media_origin
            and parsed.path.startswith('/' + path + '/'), 'Unexpected HLS origin or path')
    return result


def ffmpeg(*args):
    result = subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', *map(str, args)],
                            capture_output=True, timeout=30)
    require(result.returncode == 0, 'Media decode or sample creation failed')


def inspect(session_id, state, output, backend_client=None):
    path = 'live/' + str(session_id)
    playback = 'http://localhost:8898/' + path + '/index.m3u8'
    report = {'status': 'RUNNING', 'sessionId': str(session_id), 'playbackUrl': playback,
              'tokenSource': 'local dev key matching the backend public JWKS'}
    fixture.write_private_json(output / 'report.json', report)
    try:
        if backend_client is None:
            with urlopen('http://127.0.0.1:8082/stream/api/media/jwks', timeout=5) as response:
                backend = json.load(response)
            public = fixture.jwks(state)
            expected = {(key.get('kid'), key.get('n'), key.get('e')) for key in backend.get('keys', [])}
            available = {(key.get('kid'), key.get('n'), key.get('e')) for key in public.get('keys', [])}
            require(bool(expected & available), 'Local dev signing key does not match backend JWKS')
            token = fixture.issue(state, 'read', path, 300)
        else:
            access = backend_client.acquire(session_id)
            playback = access.playback_url
            path = access.path
            token = access.read_token
            report.update({'playbackUrl': playback, 'tokenSource': 'backend playback-access Read JWT',
                           'backendMetadataStatus': access.metadata.get('status')})
        parsed_playback = urlsplit(playback)
        require(parsed_playback.scheme == 'http', 'Playback URL must use the local HTTP media origin')
        media_origin = parsed_playback.netloc

        status, source = media.request(state, 'GET', '/v3/paths/get/' + path)
        require(status == 200 and source and source.get('online'), 'Requested media source is not online')
        report['source'] = media.safe_source(source)
        report['withoutReadTokenHttp'] = fetch(playback)[0]
        status, master, master_url = fetch(playback, token)
        require(status == 200 and master.startswith(b'#EXTM3U'), 'Cannot load authenticated HLS master playlist')
        report['masterPlaylistHttp'] = status
        variants = [line.strip() for line in master.decode().splitlines() if line.strip() and not line.startswith('#')]
        variants += re.findall(r'#EXT-X-MEDIA:[^\n]*URI="([^"]+)"', master.decode())
        require(bool(variants), 'Master playlist has no renditions')
        report['renditions'] = []
        video_file = audio_file = None
        first_playlist = None

        for index, variant in enumerate(dict.fromkeys(variants)):
            playlist_url = media_url(master_url, variant, path, media_origin)
            status, playlist, effective_url = fetch(playlist_url, token)
            require(status == 200, 'Cannot load HLS rendition playlist')
            text = playlist.decode()
            initialization = re.search(r'#EXT-X-MAP:URI="([^"]+)"', text)
            segments = [line.strip() for line in text.splitlines() if line.strip() and not line.startswith('#')]
            sequence = re.search(r'#EXT-X-MEDIA-SEQUENCE:(\d+)', text)
            require(initialization is not None and bool(segments), 'No fMP4 init or segment in rendition')
            init_status, init, _ = fetch(media_url(effective_url, initialization.group(1), path, media_origin), token)
            segment_status, segment, _ = fetch(media_url(effective_url, segments[-1], path, media_origin), token)
            require(init_status == segment_status == 200 and init and segment, 'Cannot download HLS init/segment')
            fragment = output / ('rendition-' + str(index) + '.mp4')
            fragment.write_bytes(init + segment)
            probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries',
                                    'stream=codec_name,codec_type,width,height', '-of', 'json', str(fragment)],
                                   capture_output=True, timeout=20)
            require(probe.returncode == 0, 'Downloaded media probe failed')
            streams = json.loads(probe.stdout)['streams']
            ffmpeg('-i', fragment, '-f', 'null', '-')
            detail = {'playlistHttp': status, 'initHttp': init_status, 'segmentHttp': segment_status,
                      'initBytes': len(init), 'segmentBytes': len(segment),
                      'segmentExtension': Path(urlsplit(segments[-1]).path).suffix,
                      'mediaSequence': int(sequence.group(1)) if sequence else None,
                      'lastSegment': Path(urlsplit(segments[-1]).path).name,
                      'segmentCount': len(segments),
                      'streams': streams, 'decode': 'PASS'}
            report['renditions'].append(detail)
            if any(stream['codec_type'] == 'video' for stream in streams):
                video_file = fragment
            if any(stream['codec_type'] == 'audio' for stream in streams):
                audio_file = fragment
            if first_playlist is None:
                first_playlist = (playlist_url, {'mediaSequence': detail['mediaSequence'],
                                                'lastSegment': detail['lastSegment'],
                                                'segmentCount': detail['segmentCount']})

        require(video_file is not None and audio_file is not None, 'Expected both video and audio renditions')
        frame = output / 'sample-frame.jpg'
        ffmpeg('-y', '-i', video_file, '-frames:v', '1', '-update', '1', frame)
        report['sampleFrame'] = str(frame)
        sample = output / 'sample.mp4'
        if video_file == audio_file:
            ffmpeg('-y', '-i', video_file, '-c', 'copy', sample)
        else:
            ffmpeg('-y', '-i', video_file, '-i', audio_file, '-map', '0:v:0', '-map', '1:a:0',
                   '-c', 'copy', '-shortest', sample)
        report['sampleClip'] = str(sample)

        before = first_playlist[1]
        after = before
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            time.sleep(1)
            status, updated, _ = fetch(first_playlist[0], token)
            match = re.search(rb'#EXT-X-MEDIA-SEQUENCE:(\d+)', updated)
            segments = [line.strip() for line in updated.decode().splitlines() if line.strip() and not line.startswith('#')]
            after = {'mediaSequence': int(match.group(1)) if match else None,
                     'lastSegment': Path(urlsplit(segments[-1]).path).name if segments else None,
                     'segmentCount': len(segments)}
            if status == 200 and after['lastSegment'] and after['lastSegment'] != before['lastSegment']:
                break
        advanced = bool(after['lastSegment'] and after['lastSegment'] != before['lastSegment'])
        report['playlistProgression'] = {'before': before, 'after': after, 'advanced': advanced}
        require(advanced, 'Playlist did not expose a new segment while observing the stream')
        report['status'] = 'PASS'
        return report
    except Exception as error:
        report['status'] = 'FAIL'
        report['error'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        raise RuntimeError(report['error']) from None
    finally:
        fixture.write_private_json(output / 'report.json', report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session-id', required=True, type=UUID)
    parser.add_argument('--access-mode', choices=['fixture', 'backend'], default='fixture')
    parser.add_argument('--account-cookie-file', type=Path)
    parser.add_argument('--gateway-base-url', default='http://localhost:8888')
    parser.add_argument('--state-dir', type=Path, default=fixture.DEFAULT_STATE)
    args = parser.parse_args()
    state = args.state_dir.resolve()
    backend_client = None
    if args.access_mode == 'backend':
        if args.account_cookie_file is None:
            parser.error('--account-cookie-file is required for backend access mode')
        try:
            backend_client = BackendClient(args.gateway_base_url, load_cookie(args.account_cookie_file))
        except ValueError as error:
            parser.error(str(error))
    output = state / 'playback-check' / str(args.session_id)
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(0o700)
    try:
        report = inspect(args.session_id, state, output, backend_client)
        print('PASS: source online; authenticated master/rendition/init/segment HTTP 200; audio/video decode; advancing playlist.')
        print('Token source:', report['tokenSource'])
        for rendition in report['renditions']:
            print('Media:', json.dumps(rendition['streams']), 'segment bytes:', rendition['segmentBytes'])
        print('Playlist progression:', json.dumps(report['playlistProgression']))
        print('Sample clip:', report['sampleClip'])
        print('Sample frame:', report['sampleFrame'])
    except (RuntimeError, BackendError) as error:
        print('FAIL:', error)
        return 1
    finally:
        print('Safe report:', output / 'report.json')
    return 0


if __name__ == '__main__':
    sys.exit(main())
