import json
from pathlib import Path
import tempfile
import unittest
from uuid import uuid4

from acceptance import evaluate


class BackendAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = Path(self.directory.name)
        self.session_a = str(uuid4())
        self.session_b = str(uuid4())

    def record(self, **values):
        return dict(sessionId=self.session_b, accessSource='backend', kind='browser', case='valid',
                    frames=30, seconds=3, playlistLoads=2, segmentLoads=2, audioConfirmed=True,
                    httpError=0, source={'id': 'source-1', 'type': 'rtmpConn'},
                    backendStatus='LIVE', pathConfigured=True, recordedAt='2026-10-29T01:00:00Z') | values

    def save(self, *records):
        (self.state / 'manual-results.jsonl').write_text(''.join(json.dumps(record) + '\n' for record in records))

    def results(self):
        return evaluate(self.state, self.session_b, 'backend')

    def test_backend_browser_evidence_is_read_without_legacy_session_or_hooks(self):
        self.save(self.record())
        result = self.results()
        self.assertTrue(result['browser']['passed'])
        self.assertFalse(result['obs-publish']['passed'])
        self.assertNotIn('STR-005', result)
        self.assertNotIn('M5', result)
        self.assertTrue(all(check['sessionId'] == self.session_b for check in result.values()))

    def test_other_sessions_and_sources_cannot_satisfy_backend_acceptance(self):
        self.save(self.record(sessionId=self.session_a), self.record(accessSource='fixture'),
                  {key: value for key, value in self.record().items() if key != 'accessSource'})
        (self.state / 'session.json').write_text(json.dumps({'sessionId': self.session_a}))
        (self.state / 'user-confirmation.json').write_text(json.dumps({
            'source': 'user-message', 'sessionId': self.session_a, 'statement': 'M0 completed',
            'confirmedChecks': ['browser-rejection:missing']}))
        self.assertTrue(all(not item['passed'] for item in self.results().values()))

    def test_obs_publish_reconnect_and_end_use_backend_session_records(self):
        self.save(self.record(kind='obs', step='publish'),
                  self.record(kind='obs', step='reconnect', source={'id': 'source-2', 'type': 'rtmpConn'},
                              recordedAt='2026-10-29T01:01:00Z'),
                  self.record(kind='obs', step='old-key-rejected', source=None, backendStatus='ENDED',
                              pathConfigured=False, recordedAt='2026-10-29T01:02:00Z'))
        result = self.results()
        self.assertTrue(result['obs-publish']['passed'])
        self.assertTrue(result['obs-reconnect']['passed'])
        self.assertTrue(result['obs-end']['passed'])
        self.assertFalse(result['browser']['passed'])

    def test_reconnect_cannot_borrow_publish_from_session_a(self):
        self.save(self.record(kind='obs', step='publish', sessionId=self.session_a),
                  self.record(kind='obs', step='reconnect', source={'id': 'source-2', 'type': 'rtmpConn'},
                              recordedAt='2026-10-29T01:01:00Z'))
        self.assertFalse(self.results()['obs-reconnect']['passed'])

    def test_legacy_acceptance_does_not_count_backend_records_with_same_id(self):
        (self.state / 'session.json').write_text(json.dumps({'sessionId': self.session_b}))
        self.save(self.record())
        result = evaluate(self.state)
        self.assertIn('Browser picture/audio confirmation and playback measurements', result['STR-005']['missing'])

    def test_explicit_selection_never_silently_falls_back_to_legacy_session(self):
        with self.assertRaises(ValueError):
            evaluate(self.state, access_source='backend')
        with self.assertRaises(ValueError):
            evaluate(self.state, self.session_b)


if __name__ == '__main__':
    unittest.main()
