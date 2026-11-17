#!/usr/bin/env python3
"""Start MediaMTX, restore ingest paths once, and forward container shutdown signals."""

import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, ProxyHandler

from media_hook import endpoint, HookFailure, NoRedirect


READY_FILE = Path('/tmp/media-startup-ready')
REQUEST_TIMEOUT = 5
RETRY_DELAY = 2
DEFAULT_CALLBACK = 'http://host.docker.internal:8082/stream/internal/api/media/restore-ingest-paths'


def restore_ingest_paths(environment, stopped, ready_file):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    try:
        control_url = endpoint(environment.get('MEDIA_CONTROL_BASE_URL', 'http://127.0.0.1:9997'))
        callback_url = environment.get('STREAM_MEDIA_STARTUP_CALLBACK_URL', DEFAULT_CALLBACK)
        if callback_url:
            callback_url = endpoint(callback_url)
    except HookFailure:
        print('Media startup failed; reason=invalid-url', file=sys.stderr, flush=True)
        return

    attempt = 0
    while not stopped.is_set():
        attempt += 1
        try:
            with opener.open(control_url.rstrip('/') + '/v3/info', timeout=REQUEST_TIMEOUT) as response:
                if response.status != 200 or not json.load(response).get('started'):
                    raise ValueError
            if stopped.is_set():
                return
            if callback_url:
                request = Request(callback_url, data=b'', method='POST', headers={'Accept': 'application/json'})
                with opener.open(request, timeout=REQUEST_TIMEOUT) as response:
                    result = json.load(response)
                    if (response.status != 200 or result.get('code') != 1000
                            or type(result.get('result', {}).get('restoredChannels')) is not int):
                        raise ValueError
            if not stopped.is_set():
                ready_file.touch()
                print('Media startup complete; ingest recovery acknowledged' if callback_url
                      else 'Media startup complete; ingest recovery disabled for fixture', flush=True)
            return
        except HTTPError as error:
            status = error.code
            error.close()
            print(f'Media startup waiting; attempt={attempt}, status={status}', file=sys.stderr, flush=True)
        except (URLError, TimeoutError, OSError, ValueError, AttributeError):
            print(f'Media startup waiting; attempt={attempt}', file=sys.stderr, flush=True)
        stopped.wait(RETRY_DELAY)


def main():
    READY_FILE.unlink(missing_ok=True)
    stopped = threading.Event()
    process = subprocess.Popen(sys.argv[1:] or ['/mediamtx', '/mediamtx.yml'])

    def shutdown(_signal, _frame):
        stopped.set()
        if process.poll() is None:
            try:
                process.send_signal(signal.SIGINT)
            except ProcessLookupError:
                pass

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    worker = threading.Thread(target=restore_ingest_paths, args=(os.environ, stopped, READY_FILE), daemon=True)
    worker.start()
    try:
        code = process.wait()
        return code if code >= 0 else 128 - code
    finally:
        stopped.set()
        worker.join(timeout=REQUEST_TIMEOUT + 1)
        READY_FILE.unlink(missing_ok=True)


if __name__ == '__main__':
    sys.exit(main())
