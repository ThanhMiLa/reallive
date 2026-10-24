#!/usr/bin/env python3
"""Review protocol results together with human OBS/browser observations."""

import json
from pathlib import Path
import fixture


def evaluate(state):
    protocol_file = state / 'smoke-results.json'
    protocol = json.loads(protocol_file.read_text()) if protocol_file.exists() else {}
    expected = {
        'control-api-permission-separation', 'provisioning-alone-is-not-online',
        'publish-h264-aac-and-online-hook', 'second-publisher-cannot-replace-live-source',
        'cors-authorization-preflight-and-origin-allowlist',
        'authenticated-hls-playlist-init-segment-and-av-decode',
        'new-hls-session-rejects-missing-action-path-signature-expiry',
        'existing-hls-session-not-revoked-by-expired-jwt',
        'offline-hook-matches-original-source',
        'publish-rejects-missing-action-path-signature-expiry-and-unregistered-path',
        'reconnect-same-jwt-and-path-new-source',
        'delete-live-path-stops-source-and-rejects-still-valid-old-jwt',
    }
    checks = protocol.get('checks', [])
    protocol_ok = (protocol.get('status') == 'PASS' and protocol.get('mediaVersion') == '1.21.2'
                   and {check.get('name') for check in checks if check.get('passed') is True} >= expected)
    observation_file = state / 'manual-results.jsonl'
    records = [json.loads(line) for line in observation_file.read_text().splitlines()] if observation_file.exists() else []
    session_id = json.loads((state / 'session.json').read_text())['sessionId']
    records = [record for record in records if record.get('sessionId') == session_id]
    confirmation_file = state / 'user-confirmation.json'
    confirmation = json.loads(confirmation_file.read_text()) if confirmation_file.exists() else {}
    # A user's explicit report is human evidence, kept apart from recorded HTTP measurements.
    reported = set()
    if (confirmation.get('source') == 'user-message' and confirmation.get('sessionId') == session_id
            and isinstance(confirmation.get('statement'), str) and confirmation['statement'].strip()
            and isinstance(confirmation.get('confirmedChecks'), list)):
        reported = {item for item in confirmation['confirmedChecks'] if isinstance(item, str)}
    event_file = state / 'events.jsonl'
    events = [json.loads(line) for line in event_file.read_text().splitlines()] if event_file.exists() else []
    events = [event for event in events if event['path'] == 'live/' + session_id]

    def source_id(record):
        return (record.get('source') or {}).get('id')

    def has_event(kind, identifier):
        return identifier is not None and any(event['eventType'] == kind and event['sourceId'] == identifier for event in events)

    published = [record for record in records if record.get('kind') == 'obs' and record.get('step') == 'publish'
                 and (record.get('source') or {}).get('type') in ['rtmpConn', 'rtmpsConn']
                 and has_event('ONLINE', source_id(record))]
    browser = any(record.get('kind') == 'browser' and record.get('case') == 'valid'
                  and record.get('frames', 0) > 0 and record.get('seconds', 0) >= 2
                  and record.get('playlistLoads', 0) >= 2 and record.get('segmentLoads', 0) >= 1
                  and record.get('audioConfirmed') is True and record.get('httpError') == 0
                  and source_id(record) is not None for record in records)
    negative_cases = {record.get('case') for record in records if record.get('kind') == 'browser'
                      and record.get('frames') == 0 and record.get('httpError') in [401, 403]}
    reported_negative = {case for case in ['missing', 'wrong-action', 'wrong-path'] if 'browser-rejection:' + case in reported}
    needed_negative = {'missing', 'wrong-action', 'wrong-path'} - negative_cases - reported_negative
    reconnect = any(record.get('kind') == 'obs' and record.get('step') == 'reconnect'
                    and has_event('ONLINE', source_id(record))
                    and any(source_id(first) != source_id(record) and has_event('OFFLINE', source_id(first))
                            and first['recordedAt'] < record['recordedAt'] for first in published)
                    for record in records)
    old_rejected = any(record.get('kind') == 'obs' and record.get('step') == 'old-key-rejected'
                       and record.get('source') is None and record.get('pathConfigured') is False for record in records)
    results = {
        'protocol': {'passed': protocol_ok, 'detail': '12 protocol checks on MediaMTX 1.21.2'},
        'STR-004': {'passed': protocol_ok and bool(published), 'missing': [] if published else ['OBS publish observation with matching ONLINE hook']},
        'STR-005': {'passed': protocol_ok and browser and not needed_negative,
                    'missing': ([] if browser else ['Browser picture/audio confirmation and playback measurements'])
                    + ['Browser rejected case: ' + case for case in sorted(needed_negative)],
                    'recordedRejectedCases': sorted(negative_cases),
                    'userReportedRejectedCases': sorted(reported_negative - negative_cases)},
        'STR-006': {'passed': protocol_ok and reconnect and old_rejected,
                    'missing': ([] if reconnect else ['OBS reconnect observation with old/new source hooks'])
                    + ([] if old_rejected else ['OBS old-key rejection observation after path deletion'])},
    }
    return results


def main():
    results = evaluate(fixture.DEFAULT_STATE)
    for name, result in results.items():
        print(name + ': ' + ('PASS' if result['passed'] else 'PENDING'))
        for missing in result.get('missing', []):
            print('  - ' + missing)
        reported = result.get('userReportedRejectedCases', [])
        if reported:
            print('  Human confirmation (individual HTTP records absent): ' + ', '.join(reported))
    print('This command does not change task checkboxes or invent manual observations.')
    return 0 if all(result['passed'] for result in results.values()) else 1


if __name__ == '__main__':
    raise SystemExit(main())
