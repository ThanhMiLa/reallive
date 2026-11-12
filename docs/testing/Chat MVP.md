# Chat MVP verification — 2026-11-11

Implementation: [contract](../contracts/chat/README.md), [startup/runbook](../../chat-service/README.md).

## Completed automated checks

| Module | Check | Result |
|---|---|---|
| API Gateway | `./mvnw clean verify` | 92 tests, 0 failures/errors, 1 skipped |
| Channel Service | `./mvnw clean verify` | 497 tests, 0 failures/errors |
| Stream Service | `./mvnw clean verify` | 338 tests, 0 failures/errors |
| User Service | `./mvnw clean verify` | 741 tests, 0 failures/errors, 36 skipped |
| Chat Service | Build, Oxlint, `tsc --noEmit`, unit tests | Passed; 4 unit tests |
| Chat Service | `npm run test:integration` | Passed; 17 integration scenarios; baseline scenario skipped in the normal run |
| Chat Service | `npm run test:baseline` | Passed separately; JSON report recorded |
| Web | Build, ESLint, `npm test` | Passed; 41 existing tests |
| Web | `npm run test:streaming` | Passed; 67 tests including 8 chat cases |

The skipped Java cases are opt-in environment-specific suites; a successful default verify does not claim those scenarios ran. Vite still reports large bundle chunks; compilation and lint complete successfully.

## Integration evidence

Chat tests start disposable, uniquely named MongoDB replica-set and Redis containers, use a random `chat_it_...` database and mock the three Java integration APIs over real HTTP without authentication; fixtures reject an obsolete internal-token header. They exercise real Socket.IO WebSocket handshakes and HTTP history requests. Test containers and databases are removed after each run.

Covered: current account/JWT validation, refresh-token rejection, malformed token, Origin allowlist, current channel permissions, read-only chat ban, changed permissions checked on the next action, LIVE/RECONNECTING/ENDED, no idle permission polling, delivery after access-cache expiry, revoked access token checked on the next action, 500 Unicode code points, literal HTML text, server-controlled identity/snapshot, idempotent retry/conflict, transaction rollback, application restart, concurrent sends, equal-timestamp history, both pagination directions, cross-session cursor rejection, empty tail recovery, bootstrap watermark catch-up, dependency failure and indexed deep-page queries.

Gateway tests proxy authenticated HTTP and actual WebSocket frames through a random Gateway port to a real local backend; unauthenticated upgrades and internal route exposure are rejected. This verifies transport routing independently of the Socket.IO application tests.

Channel integration tests exercise latest BAN/UNBAN for every type, equal timestamps with event_order, concurrent active ban types, anonymous internal access, single-user request validation and inactive/deleted channels. Private User/Stream endpoints have anonymous-access/response tests. Playback tests cover denial before minting credentials. Avatar regression tests verify committed old images are retained and new uploads are cleaned on rollback.

Web tests cover additive history loading, bounded 20-page eviction with reverse retrieval, no unbounded realtime accumulation while reading old messages, literal text, Unicode validation, same-ID retry after unconfirmed delivery, read-only ban, closure/cleanup and gap recovery without duplicate rendering. Virtualizer layout is mocked in component tests; this is not a browser rendering or scroll-geometry measurement.

## Local baseline

Full measured data: [Chat Baseline.json](Chat%20Baseline.json).

- 100,000 persisted messages; 20 connected clients.
- 100 send RTT samples and 100 history RTT samples, in batches of 10.
- Send RTT p50/p95/p99: **92.92 / 237.90 / 282.06 ms**.
- History RTT p50/p95/p99: **34.40 / 79.02 / 84.84 ms**.
- Deep-page query: **50 returned, 50 index keys examined, 50 documents examined**.
- Combined measured workload: 83.64 operations/second over 2.39 seconds.

This is a short local baseline, not a saturation test or a production capacity/SLO claim. Nest, fixture APIs and clients run in the Jest process; Gateway is excluded. Other verification processes were active on the workstation. Reported RSS includes Jest, seed allocation and test clients; it must not be interpreted as standalone Chat Service memory. MongoDB/Redis container CPU/RAM and fanout end-to-end browser latency are not measured by this report.

## Visual QA limitation and follow-up

A local isolated UI fixture was prepared with the actual panel, backend, MongoDB and Redis. Browser access was unavailable and native Computer Use permissions were not granted, so visual inspection/screenshots could not be completed. The temporary preview files/processes were removed. No real account credentials or application database were used.

When browser access is available, verify desktop/mobile web layout, variable-height messages, keeping the same visible message when prepending or evicting pages, reading old messages during new arrivals, Back to latest, keyboard sending/retry, and closed/read-only states. Capture screenshots for UI review.

## Deployment status

No application database migration, existing service restart or production deployment was performed. Configure the existing auth Redis/signing key, replica-set URI and indexes before enabling the feature. Follow the runbook deployment order. Retention/spam/moderation management/VOD replay/Android and multi-instance chat remain outside this MVP.
