# Stream Service

Java 21 / Spring Boot service responsible for livestream sessions, stream keys, viewer state and Media Server coordination.

## Common infrastructure

```text
com.reallive.streamservice/
├── StreamServiceApplication.java
└── common/
    ├── adapter/redis/    # Reads current authorities from Redis
    ├── configuration/    # String-based Redis configuration
    ├── dto/              # ApiResponse
    ├── entity/           # BaseEntity with creation/update timestamps
    ├── exception/        # AppException, ErrorCode, GlobalExceptionHandler
    ├── security/         # Cookie JWT authentication and method authorization
    └── util/             # AuthenticationUserId
```

New streaming features belong under a business module such as `stream`, with its own `controller`, `service`, `service.impl`, `repository`, `entity`, `mapper` and `dto.request` / `dto.response` packages as needed. Media Server, Kafka and viewer Redis integrations belong under that module's `adapter`; calls to User Service or Channel Service belong under its `client`.

Security follows the [Auth State contract](../docs/contracts/Auth%20State.md). Gateway validates JWT signatures, lifetime, token type, revocation and account status. Stream Service reads the forwarded access-token cookie and loads current authorities from Redis on each authenticated request, ignoring JWT scope. Backend ports are restricted to trusted callers in this model. Business endpoints require authentication. The exact `GET /api/media/jwks` route is `permitAll`, and `POST /internal/api/media-events` uses constant-time `X-Internal-Token` authentication (`InternalCallbackTokenFilter`).

Authentication and authorization failures use `ApiResponse` with HTTP 401/403. Missing, corrupt or unavailable Redis authorities return HTTP 503 without clearing cookies. Application errors, invalid requests and database failures also use the common response envelope.

## Development

Copy `stream-service/.env.example` to `stream-service/.env`, set `STREAM_DB_PASSWORD`, `REDIS_PASSWORD` and the `STREAM_MEDIA_*` secrets/keys, then export the variables or load the file through the IDE run configuration. Spring Boot does not automatically load `.env` files.

The development profile defaults to `http://localhost:8082/stream`, PostgreSQL at `localhost:5434/reallive_stream`, Redis at `localhost:6379` (database `0`) and Kafka at `localhost:9092`. Redis must match the instance and logical database used by User Service and Gateway.

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

`application-dev.yaml` includes the database, JPA/Flyway, Redis, Kafka producer, access-token cookie and downstream service settings. Flyway is enabled and Hibernate validates the schema. Migrations V1–V5 under `src/main/resources/db/migration` maintain the schema (V4 adds thumbnail scheduling; V5 adds persistent channel configs, encrypted credentials, durable media operations, and runtime state).

## Validation

```shell
./mvnw clean verify
```

Tests use H2 for HTTP/application checks and isolated PostgreSQL 17 Testcontainers for Flyway, persistence and concurrency checks. Docker is required. Redis authorities are mocked; test media keys and AES secrets are generated in memory, so application credentials are not required. The real-MediaMTX integration tests (`MediaMtxAuthenticationIT`, `StreamProvisioningMediaIT`, `StreamViewerPresenceMediaIT`) are opt-in as described below.

## Persistent stream keys, media authentication, and recovery (V5)

Channel streaming uses persistent channel-bound ingest keys and separated per-session playback paths (see [Streaming Persistent Key Refactor Plan](../docs/implement-plan/Streaming%20Persistent%20Key%20Refactor%20Plan.md)):

- **Ingest path** (`ingest/{ingestKeyId}`): configured as `source: publisher`, `overridePublisher: false`, `runOnRead: ""`, `runOnUnread: ""`. Receives OBS RTMP/RTMPS with a permanent `Publish JWT` (no `exp`).
- **Playback path** (`live/{sessionId}`): configured with `source: rtmp://127.0.0.1:1935/ingest/{ingestKeyId}?token={RelayReadJWT}`, `sourceOnDemand: false`, `runOnOnline: ""`, `runOnOffline: ""`. Serves HLS (`/live/{sessionId}/index.m3u8`) using short-lived viewer/worker `Read JWT` (5-minute TTL, cannot read `ingest/*`) and runs CCU reader hooks only on playback paths.

### Required cryptographic keys and operational rotation

The service requires stable RS256 keys (`>= 2048` bits), a 32-byte AES-256-GCM encryption key (`STREAM_MEDIA_CREDENTIAL_ENCRYPTION_KEY`), and an internal webhook token (`STREAM_MEDIA_CALLBACK_INTERNAL_TOKEN`, 32+ non-whitespace characters) at startup:

```sh
./scripts/dev/generate-stream-media-keys.sh
export STREAM_MEDIA_PRIVATE_KEY_BASE64="$(openssl pkcs8 -topk8 -nocrypt -in .local/streaming/private.pem -outform DER | base64 | tr -d '\n')"
export STREAM_MEDIA_PUBLIC_KEY_BASE64="$(openssl pkey -pubin -in .local/streaming/public.pem -outform DER | base64 | tr -d '\n')"
export STREAM_MEDIA_CREDENTIAL_ENCRYPTION_KEY="$(openssl rand -base64 32 | tr -d '\n')"
```

Keep `STREAM_MEDIA_*` variables private and load database/Redis settings through `stream-service/.env`. Startup fails with a sanitized message on missing/mismatched RSA keys, malformed 32-byte AES key, blank/short callback token, or invalid URLs/TTLs. Keys are **never** auto-generated at runtime and must remain stable across restarts. Rotating RS256 signing keys or AES-256-GCM encryption keys is an explicit offline/maintenance operational procedure (dual-publish JWKS / re-encrypt or rotate persisted `channel_stream_credentials` rows cleanly before retiring old key material).

Defaults: Publish JWT & internal RelayRead JWT omit `exp` (persist until revoked/reset); Read JWT & Control API JWT default to 5 minutes; issuer/audience `reallive-stream` / `reallive-media`, key ID `stream-rs256-v1`, public RTMP URL `rtmp://localhost:1935/ingest`, internal RTMP base URL `rtmp://127.0.0.1:1935`.

- `GET /stream/api/media/jwks` (`port 8082`) returns the raw public JWKS (`permitAll`).
- `POST /stream/internal/api/media-events` (`port 8082`) accepts media callbacks from `media_hook.py` authenticated with `X-Internal-Token` (`STREAM_MEDIA_CALLBACK_INTERNAL_TOKEN`), deduplicates by `eventId`, persists `PROCESS_ONLINE`/`PROCESS_OFFLINE` operations, and returns `202 Accepted`. Not exposed through API Gateway.
- User and Channel clients call internal eligibility, streaming-context, category validation, and bounded batch-summary endpoints synchronously (`2s` connect / `5s` read timeouts, no retries).

### MediaMTX integration verification

Prerequisites: Docker, FFmpeg with H.264/AAC, JDK 21, and `reallive-media-server:1.21.2`. From repository root:

```sh
docker build -f media-server/docker/Dockerfile -t reallive-media-server:1.21.2 .
./stream-service/mvnw -f stream-service/pom.xml -Dtest=MediaMtxAuthenticationIT -Dstream.media.it=true test
./stream-service/mvnw -f stream-service/pom.xml -Dtest=StreamProvisioningMediaIT -Dstream.provisioning.it=true test
./stream-service/mvnw -f stream-service/pom.xml -Dtest=StreamViewerPresenceMediaIT -Dstream.ccu.it=true test
```

These opt-in integration suites run isolated PostgreSQL + Stream HTTP + MediaMTX `1.21.2` + Redis containers on random ports using real hooks and synthetic FFmpeg RTMP publishing (verifying permanent Publish JWTs, internal RTMP pull without hook loops, playback separation, CCU presence, reconnect, and key reset). They are excluded from default `./mvnw clean verify` via `@EnabledIfSystemProperty` and must be run explicitly with those flags.

## Owner & public streaming APIs

Owner endpoints (Gateway prefix `/stream`; controller prefix `/api/streams`; all credential/setup/current/playback responses carry `Cache-Control: no-store`):

| Method/path | Behavior |
|---|---|
| GET `/stream/api/streams/me/setup` | `STREAM_VIEW_OWN` + `STREAM_PUBLISH`. Lazy conflict-safe creation of `DRAFT` config (`streamKey = null` until provisioned); returns current setup state (`DRAFT`, `PROVISIONING`, `ACTIVE`, `ROTATING`, `BLOCKED`) and active Publish key when ready |
| PUT `/stream/api/streams/me/setup` | `STREAM_UPDATE` + `STREAM_PUBLISH`. Validates title/category & eligibility; first valid Save creates generation 1 (`channel_stream_credentials`), `PROVISIONING` status, and `PROVISION_INGEST` op; subsequent Saves update config and active session without changing stream key |
| POST `/stream/api/streams/me/stream-key/reset` | `STREAM_PUBLISH` + `Idempotency-Key` header -> returns `202 Accepted` (`StreamOperationResponse`). Revokes current key generation, marks active session end-requested, moves config to `ROTATING`, and enqueues `RESET_KEY` |
| GET `/stream/api/streams/me/operations/{operationId}` | `STREAM_VIEW_OWN`. Returns durable operation progress for provisioning/reset polling |
| GET `/stream/api/streams/me/current` | `STREAM_VIEW_OWN`. Returns active `LIVE`/`RECONNECTING` session (never returns credentials; `null` when offline) |
| GET `/stream/api/streams` | Public discovery list of `LIVE` streams (paginated, optional `categoryId`, includes `currentViewerCount`) |
| GET `/stream/api/streams/{sessionId}` | Session metadata, status, `serverTime`, `reconnectDeadlineAt`, `currentViewerCount` |
| GET `/stream/api/streams/{sessionId}/details` | Channel & category details for a session |
| POST `/stream/api/streams/{sessionId}/playback-access` | Authenticated channel-access check -> issues 5m Read JWT limited strictly to `live/{sessionId}` |

Legacy manual-session mappings (`POST /api/streams`, `PATCH /api/streams/{id}`, `POST /api/streams/{id}/publish-credentials`, `POST /api/streams/{id}/end`, and `POST /api/media/events`) are removed.

## Durable operation worker & reconciliation

- `StreamMediaOperationWorkerImpl` polls PostgreSQL (`stream_media_operations`) every `1s` (`stream.lifecycle.operation-worker-delay`), claims due operations with a `60s` lease and `20s` per-run budget, retries transport/5xx errors with backoff (`1s, 2s, 5s, 10s, max 30s`), locks `channel_stream_configs` before either operation or session rows, and performs MediaMTX/Feign HTTP I/O outside DB transactions.
- Generation (`credential_generation`), revision (`media_revision`), and `end_requested` checks fence stale callbacks/operations before committing transitions.
- `ReconnectExpirationScheduler` scans expired `RECONNECTING` sessions every `1s` (`60s` default grace) and drives idempotent closure (`END_SESSION`).
- Media effects are serialized per channel across the worker, reconciler cleanup and reconnect expiration using PostgreSQL session advisory locks on dedicated connections. Contention defers work instead of blocking a business transaction or consuming its pool. This requires a direct PostgreSQL connection or session pooling; transaction pooling is unsuitable for these locks. Each worker claim also has a unique ownership token checked before commit/retry; successful playback is never treated as an uncommitted candidate for cleanup.
- Restart recovery advances the observed server-instance watermark only after all old-session closure operations are durable. Reconciliation repair operations have separate cycle IDs so a previous successful repair cannot suppress later repairs. A changed publisher without a reliable OFFLINE timestamp starts a new session.
- `StreamMediaReconcilerImpl` runs at startup and every `5s` (`stream.lifecycle.reconciler-delay`): detects MediaMTX restarts via `stream_media_runtime_state`, clears revoked ingests and ended/orphaned playback paths before re-registering `ACTIVE` ingests, and synthesizes missed `ONLINE`/`OFFLINE` operations.
- Micrometer metrics (no tokens in labels): `stream.media.operations.pending`, `stream.media.operations.oldest_age_seconds`, `stream.media.operations.retries`, `stream.media.reconciliation.errors`, `stream.media.callback.delay`, `stream.media.provision.duration`, `stream.media.reset.duration`.

## Livestream thumbnail callbacks

`POST /stream/internal/api/thumbnail-results` accepts thumbnail results directly from Media Worker, outside Gateway routes. Enable it independently with `STREAM_THUMBNAIL_CALLBACK_ENABLED=true`; set `STREAM_SERVICE_INTERNAL_TOKEN` to the Worker's callback secret, `R2_PUBLIC_BASE_URL` to the thumbnail bucket's public URL and optionally `STREAM_THUMBNAIL_MAX_JPEG_BYTES` (default 5242880). Account cookies do not authenticate this endpoint.

The backend locks the session row, checks the current waiting job ID, deadline and LIVE state, then atomically stores the URL/object key, UTC `thumbnail_updated_at` and SUCCEEDED status. Job ID and deadline are retained. Responses are plain `{"outcome":"APPLIED"}` or `{"outcome":"IGNORED"}`. No R2 request is made inside the transaction.

`STREAM_THUMBNAIL_ENABLED` controls dispatch separately. The dev profile defaults dispatch and callback to enabled; `.env.example` explicitly disables both for deliberate startup. Worker processing remains disabled by default. Configure the callback endpoint before enabling Worker processing and dispatch. Callback failures are not retried; the existing scheduler expires waiting jobs and permits another capture.

Manual livestream/R2/DB acceptance was confirmed on 2026-11-06. Automated full-pipeline integration and remaining concurrency/shutdown scenarios are deferred by the user; they are not reported as passing.

### List live streams

`GET /stream/api/streams?page=0&size=20&categoryId=<uuid>` requires authentication.
`categoryId` is optional and filters the category of the stream session. The endpoint
returns only `LIVE` sessions. `page` is zero-based; `size` defaults to 20 and is
limited to 100. The response uses the standard `ApiResponse` envelope with `content`,
`page`, `size`, `totalElements`, and `totalPages` in `result`. Each item contains the
session ID, title, status, UTC start time, thumbnail URL and update time, channel and
category IDs, and available channel/category summaries. Thumbnail and summary fields
can be null. No ordering is guaranteed; pages can change as live sessions change.
A Channel Service failure returns `503`.
