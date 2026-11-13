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

Security follows the [Auth State contract](../docs/contracts/Auth%20State.md). Gateway validates JWT signatures, lifetime, token type, revocation and account status. Stream Service reads the forwarded access-token cookie and loads current authorities from Redis on each authenticated request, ignoring JWT scope. Backend ports are restricted to trusted callers in this model. Business endpoints require authentication. The exact `GET /api/media/jwks` route is `permitAll`, and internal service endpoints use a separate `permitAll` filter chain with no account JWT or internal-token verification. Matchers are scoped to the supported paths and HTTP methods; Gateway does not route these paths.

Authentication and authorization failures use `ApiResponse` with HTTP 401/403. Missing, corrupt or unavailable Redis authorities return HTTP 503 without clearing cookies. Application errors, invalid requests and database failures also use the common response envelope.

## Development

Copy `stream-service/.env.example` to `stream-service/.env`, set `STREAM_DB_PASSWORD`, `REDIS_PASSWORD` and the `STREAM_MEDIA_*` secrets/keys, then export the variables or load the file through the IDE run configuration. Spring Boot does not automatically load `.env` files.

The development profile defaults to `http://localhost:8082/stream`, PostgreSQL at `localhost:5434/reallive_stream`, Redis at `localhost:6379` (database `0`) and Kafka at `localhost:9092`. Redis must match the instance and logical database used by User Service and Gateway.

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

`application-dev.yaml` includes the database, JPA/Flyway, Redis, Kafka producer, access-token cookie and downstream service settings. Flyway is enabled and Hibernate validates the schema. Migrations V1–V6 under `src/main/resources/db/migration` maintain the schema. V4 adds thumbnail scheduling; V6 reduces the streaming model to channel settings, sessions and status history. V5 is retained as migration history.

## Validation

```shell
./mvnw clean verify
```

Tests use H2 for HTTP/application checks and isolated PostgreSQL 17 Testcontainers for Flyway, persistence and concurrency checks. Docker is required. Redis authorities are mocked; test media keys are generated in memory, so application credentials are not required. The real-MediaMTX integration tests (`MediaMtxAuthenticationIT`, `StreamProvisioningMediaIT`, `StreamViewerPresenceMediaIT`) are opt-in as described below.

## Synchronous persistent stream keys (V6)

See [architecture and workflow](../docs/architecture/Streaming%20Architecture%20and%20Flow.md).

- OBS publishes to `ingest/{ingestKeyId}` using RS256 Publish JWT without exp. MediaMTX pulls internally into `live/{sessionId}` and serves HLS. Viewers/thumbnail use session-scoped Read JWT (5m).
- GET `/stream/api/streams/me/setup`: lazy DRAFT settings, never returns credentials. PUT saves title/category and updates the active session, without issuing a key.
- POST `/stream/api/streams/me/stream-key`: synchronously closes the previous broadcast, kicks the old publisher, deletes the old ingest and creates a new ingest. Returns `200` with `channelId`, `serverUrl`, `streamKey` once. No idempotency header or operation polling. A later call always replaces the key.
- Copying the key or leaving the manager clears its local UI state. GET/Save/reload never return a previous key. Keys already configured in OBS continue working across sessions until replacement.
- Media callbacks POST `/stream/internal/api/media-events` process ONLINE/OFFLINE synchronously and return `200` APPLIED/IGNORED. OBS Start creates LIVE only after playback is ready; Stop starts a 60s grace. The reconnect scheduler expires sessions directly.
- Three business tables: channel_stream_configs, stream_sessions, stream_status_history. No credential/token persistence, operation queue, media recovery worker, runtime-state table, advisory lock or lease. Config row locks precede session locks and remain held through bounded Control API calls.
- RSA keys must remain stable. Configure `STREAM_MEDIA_PRIVATE_KEY_BASE64`, `STREAM_MEDIA_PUBLIC_KEY_BASE64`, RTMP/HLS/Control URLs. AES credential encryption is no longer required.
- Media errors return immediately; there is no automatic retry/recovery after crash or MediaMTX restart. DB rollback cannot undo media changes. An interrupted operation may require a fresh key request or manual path cleanup.

### Migration and verification

V1–V5 are unchanged. V6 preserves channel/current ingest and session history while dropping the credential, operation and runtime-state tables. Finish V5 PROVISIONING/ROTATING configs before cutover; V6 fails fast if any remain. Do not rewrite applied migrations.

```sh
./mvnw clean verify
./mvnw test -Dtest=MediaMtxAuthenticationIT,StreamProvisioningMediaIT,StreamViewerPresenceMediaIT -Dstream.media.it=true -Dstream.provisioning.it=true -Dstream.ccu.it=true
```

The opt-in suites use isolated PostgreSQL/Redis/MediaMTX and FFmpeg, not the running application stack or OBS. Owner smoke script `scripts/testing/streaming/provisioning.py` calls the one-time issue API and saves its final response privately for the operator; never print token values.

## Livestream thumbnail callbacks

`POST /stream/internal/api/thumbnail-results` accepts thumbnail results directly from Media Worker, outside Gateway routes. Enable it independently with `STREAM_THUMBNAIL_CALLBACK_ENABLED=true`; set `R2_PUBLIC_BASE_URL` to the thumbnail bucket's public URL and optionally `STREAM_THUMBNAIL_MAX_JPEG_BYTES` (default 5242880). This internal endpoint accepts calls without credentials and ignores account cookies.

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
