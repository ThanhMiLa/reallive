#!/usr/bin/env python3
"""Send MediaMTX ingest source events to stream-service with bounded retries."""

from datetime import datetime, timezone
import json
import os
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
INGEST_PREFIX = 'ingest/'


class HookFailure(Exception):
    """Represent a sanitized failure safe for stderr logs."""


class HookCancelled(Exception):
    """Stop retrying cleanly when MediaMTX terminates the hook process."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def endpoint(value):
    try:
        parsed = urlsplit(value)
        if (parsed.scheme not in ['http', 'https'] or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.query or parsed.fragment):
            raise ValueError
        parsed.port
    except (TypeError, ValueError):
        raise HookFailure('invalid-callback-url') from None
    return value


def cancelled(_signal, _frame):
    raise HookCancelled


def deadline_exceeded(_signal, _frame):
    raise HookFailure('deadline-exceeded')


class MediaHook:
    def __init__(self, environment):
        self.environment = environment
        self.deadline = time.monotonic() + TOTAL_TIMEOUT
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def remaining(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise HookFailure('deadline-exceeded')
        return remaining

    def run(self, action):
        if action not in ['online', 'offline']:
            raise HookFailure('invalid-action')
        url = endpoint(self.environment.get(
            'STREAM_MEDIA_CALLBACK_URL',
            'http://host.docker.internal:8082/stream/internal/api/media-events',
        ))
        path = self.environment.get('MTX_PATH', '')
        if not path.startswith(INGEST_PREFIX):
            raise HookFailure('invalid-ingest-path')
        try:
            ingest_key_id = str(UUID(path[len(INGEST_PREFIX):]))
            source_id = str(UUID(self.environment['MTX_SOURCE_ID']))
        except (KeyError, ValueError):
            raise HookFailure('invalid-source-metadata') from None
        source_type = self.environment.get('MTX_SOURCE_TYPE', '')
        if source_type not in ['rtmpConn', 'rtmpsConn']:
            raise HookFailure('invalid-source-type')

        event = {
            'eventId': str(uuid4()),
            'eventType': action.upper(),
            'path': INGEST_PREFIX + ingest_key_id,
            'sourceId': source_id,
            'sourceType': source_type,
            'occurredAt': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'),
        }
        body = json.dumps(event, separators=(',', ':')).encode()
        for attempt in range(MAX_ATTEMPTS):
            timeout = min(REQUEST_TIMEOUT, self.remaining())
            request = Request(
                url,
                data=body,
                headers={
                    'Content-Type': 'application/json',
                    'Accept': 'application/json',
                },
                method='POST',
            )
            try:
                with self.opener.open(request, timeout=timeout) as response:
                    if 200 <= response.status < 300:
                        return 0
            except HTTPError as error:
                status = error.code
                error.close()
                if status not in [409, 429, 500, 502, 503, 504]:
                    print(f'Media callback rejected; eventId={event["eventId"]}, status={status}', file=sys.stderr)
                    return 1
            except (URLError, TimeoutError, OSError):
                pass
            if attempt + 1 < MAX_ATTEMPTS:
                time.sleep(min(0.2 * (attempt + 1), self.remaining()))

        print(f'Media callback unavailable; eventId={event["eventId"]}', file=sys.stderr)
        return 1


def main():
    try:
        if len(sys.argv) != 2:
            raise HookFailure('invalid-action')
        signal.signal(signal.SIGINT, cancelled)
        signal.signal(signal.SIGTERM, cancelled)
        if hasattr(signal, 'setitimer'):
            signal.signal(signal.SIGALRM, deadline_exceeded)
            signal.setitimer(signal.ITIMER_REAL, TOTAL_TIMEOUT)
        return MediaHook(os.environ).run(sys.argv[1])
    except HookCancelled:
        return 0
    except HookFailure as error:
        print(f'Media hook failed; reason={error}', file=sys.stderr)
        return 1
    except Exception:
        print('Media hook failed; reason=unexpected-error', file=sys.stderr)
        return 1
    finally:
        if hasattr(signal, 'setitimer'):
            signal.setitimer(signal.ITIMER_REAL, 0)


if __name__ == '__main__':
    sys.exit(main())
