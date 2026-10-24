#!/usr/bin/env python3
"""Send authenticated MediaMTX source events without copying publisher queries."""

from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
import signal
import sys
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID, uuid4


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ['online', 'offline']:
        raise ValueError('Expected online or offline')
    secret = os.environ.get('STREAM_MEDIA_CALLBACK_SECRET', '').encode()
    url = os.environ.get('STREAM_MEDIA_CALLBACK_URL', '')
    if len(secret) < 32 or not url.startswith(('http://', 'https://')):
        raise ValueError('Missing callback URL/secret configuration')
    path = os.environ.get('MTX_PATH', '')
    if not path.startswith('live/'):
        raise ValueError('Unexpected media path namespace')
    UUID(path[5:])
    source_id = str(UUID(os.environ['MTX_SOURCE_ID']))
    source_type = os.environ['MTX_SOURCE_TYPE']
    if source_type not in ['rtmpConn', 'rtmpsConn']:
        raise ValueError('Unexpected source type')
    event = {'eventId': str(uuid4()), 'eventType': sys.argv[1].upper(), 'path': path,
             'sourceId': source_id, 'sourceType': source_type,
             'occurredAt': datetime.now(timezone.utc).isoformat()}
    body = json.dumps(event, separators=(',', ':')).encode()
    stopped = threading.Event()
    signal.signal(signal.SIGINT, lambda *_args: stopped.set())
    signal.signal(signal.SIGTERM, lambda *_args: stopped.set())
    deadline = time.monotonic() + 30
    for delay in [0, 1, 2, 4, 8]:
        if stopped.wait(delay) or time.monotonic() >= deadline:
            return 1
        timestamp = str(int(time.time()))
        signature = hmac.new(secret, timestamp.encode() + b'\n' + body, hashlib.sha256).hexdigest()
        request = Request(url, data=body, headers={'Content-Type': 'application/json',
                          'X-Media-Timestamp': timestamp, 'X-Media-Signature': 'sha256=' + signature})
        try:
            with urlopen(request, timeout=min(3, max(0.1, deadline - time.monotonic()))) as response:
                if 200 <= response.status < 300:
                    return 0
        except HTTPError as error:
            status = error.code
            error.close()
            if status < 500 and status != 429:
                break
        except (URLError, TimeoutError, OSError):
            pass
    print('Media callback failed; eventId=' + event['eventId'], file=sys.stderr)
    return 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, KeyError):
        print('Invalid media callback configuration or source metadata', file=sys.stderr)
        sys.exit(1)
