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

Security follows the [Auth State contract](../docs/contracts/Auth%20State.md). Gateway validates JWT signatures, lifetime, token type, revocation and account status. Stream Service reads the forwarded access-token cookie and loads current authorities from Redis on each authenticated request, ignoring JWT scope. Backend ports are restricted to trusted callers in this model. Business endpoints require authentication. The exact GET JWKS and POST media callback routes use the separate media security rules described below.

Authentication and authorization failures use `ApiResponse` with HTTP 401/403. Missing, corrupt or unavailable Redis authorities return HTTP 503 without clearing cookies. Application errors, invalid requests and database failures also use the common response envelope.

## Development

Copy `stream-service/.env.example` to `stream-service/.env`, set `STREAM_DB_PASSWORD`, `REDIS_PASSWORD` and the `STREAM_MEDIA_*` keys, then export the variables or load the file through the IDE run configuration. Spring Boot does not automatically load `.env` files.

The development profile defaults to `http://localhost:8082/stream`, PostgreSQL at `localhost:5434/reallive_stream`, Redis at `localhost:6379` (database `0`) and Kafka at `localhost:9092`. Redis must match the instance and logical database used by User Service and Gateway.

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

`application-dev.yaml` includes the database, JPA/Flyway, Redis, Kafka producer, access-token cookie and downstream service settings. Flyway is enabled and Hibernate validates the schema. Migrations V1–V3 under `src/main/resources/db/migration` produce the two-table session/history schema. Media authentication and the synchronous Control API adapter are configured; see the phase 3 section below.

## Validation

```shell
./mvnw clean verify
```

Tests use H2 for HTTP/application checks and isolated PostgreSQL 17 Testcontainers for Flyway, persistence and concurrency checks. Docker is required. Redis authorities are mocked; test media keys are generated in memory, so application credentials are not required. The direct Stream Service/MediaMTX check is opt-in as described below.

## Streaming phase 2: eligibility and media authentication

Phase 1 domain/persistence, phase 2 authentication, phase 3 provisioning and phase 4 synchronous lifecycle/End APIs are implemented.
Playback-access and frontend integration remain in phases 5–6.
Session writes use pessimistic row locks; no session version or media-sync task is used.

The service requires stable RSA keys at startup. From repository root:

```sh
./scripts/dev/generate-stream-media-keys.sh
export STREAM_MEDIA_PRIVATE_KEY_BASE64="$(openssl pkcs8 -topk8 -nocrypt -in .local/streaming/private.pem -outform DER | base64 | tr -d '\n')"
export STREAM_MEDIA_PUBLIC_KEY_BASE64="$(openssl pkey -pubin -in .local/streaming/public.pem -outform DER | base64 | tr -d '\n')"
```

The variables contain Base64 of DER key bytes, without PEM headers. Keep these environment values private and load the other database/Redis settings through `stream-service/.env`. Startup fails with a sanitized message for missing/mismatched RSA keys, keys below
2048 bits or invalid media URLs/TTLs. Keys are not regenerated
on restart. See `stream-service/.env.example` for media variables.

Defaults: Publish JWT 8 hours, Read JWT 5 minutes, Control API JWT 5 minutes; issuer/audience
`reallive-stream` / `reallive-media`, key ID `stream-rs256-v1`.

- `GET /stream/api/media/jwks` on Stream Service (`port 8082`) returns a raw public JWKS without account cookies (`permitAll`).
  Supplied cookies still follow the existing Auth State contract.
- `POST /stream/api/media/events` on Stream Service (`port 8082`) accepts direct JSON callbacks from MediaMTX (`permitAll`, without account cookies or HMAC secret for local MVP). Gateway does not expose `/stream/api/media/**`.
- There is deliberately no lifecycle handler yet: a valid callback currently reaches HTTP 404,
  while invalid request bodies return 400. No callback is acknowledged as processed with a fake 200.
- User and Channel clients call the internal eligibility, streaming-context, category validation and
  bounded batch-summary endpoints synchronously with 2-second connect / 5-second read timeouts and
  no retries. User/streamer/channel IDs must match; invalid or unavailable upstream responses fail closed.

Default `./mvnw clean verify` now also runs isolated PostgreSQL 17 tests using Testcontainers, so Docker
is required; application databases, OBS and MediaMTX are not required. Test RSA keys are generated in
memory and never committed.

### MediaMTX integration verification

Prerequisites: Docker, FFmpeg with H.264/AAC, JDK 21, and the locally built media image. From repository root:

```sh
docker build -f media-server/docker/Dockerfile -t reallive-media-server:1.21.2 .
./stream-service/mvnw -f stream-service/pom.xml -Dtest=MediaMtxAuthenticationIT -Dstream.media.it=true test
```

The explicit integration test starts Stream Service with H2/test keys and
one disposable MediaMTX container on random ports. MediaMTX fetches JWKS directly from Stream Service. Java-issued
JWTs are checked against real publish/read/api endpoints; invalid tokens and media JWT account-cookie
misuse are rejected. Direct callback JSON is checked against Stream Service. RTMP uses a synthetic
FFmpeg source; no OBS/browser automation is performed. Paths and container are cleaned up.
The existing application MediaMTX and databases are not reconfigured. This opt-in IT is not part of the
default Surefire test selection and must be executed before recording phase 2 runtime acceptance.

## Streaming phase 3: synchronous provisioning

Owner endpoints (Gateway prefix `/stream`; controller prefix `/api/streams`):

| Method/path | Behavior |
|---|---|
| POST `/stream/api/streams` | Create PUBLIC READY session, provision exact media path and commit before returning OBS credentials; 201 first time, 200 for the same owner/request ID |
| PATCH `/stream/api/streams/{id}` | Update supplied title/category only; owner and nonterminal session required |
| GET `/stream/api/streams/me/current` | Active owner session or result null; media readiness/deadline/serverTime without credentials |
| POST `/stream/api/streams/{id}/publish-credentials` | Fresh Publish JWT for the same session/path after owner, eligibility, state, grace and media checks |
| POST `/stream/api/streams/{id}/end` | Synchronously delete media path, kick source connection, and commit session to ENDED/CLOSED (idempotent if already ENDED) |

Create takes `requestId` (UUID), `title` and `categoryId`. Keep the same request ID after timeout/lost response; replay preserves the original metadata. A different request ID while the channel has an active session returns 409. Create and publish-credentials use `Cache-Control: no-store`. Tokens are not persisted or hashed, and issuing another JWT does not revoke previous tokens.

`MediaServerAdapter` and `MediaJwtIssuer` live under `stream/adapter/media`; their implementations (`MediaMtxAdapter`, `MediaJwtIssuerImpl`) live under `stream/adapter/media/impl`. User/Channel clients stay under `stream/client`. Control API settings:

```dotenv
STREAM_MEDIA_CONTROL_API_URL=http://localhost:9997
STREAM_MEDIA_CONTROL_CONNECT_TIMEOUT=2s
STREAM_MEDIA_CONTROL_REQUEST_TIMEOUT=5s
```

Requests block until media success/timeout, with no retry, redirect or background task. The use case owns the write transaction, reserves the channel slot before media I/O and holds session locks through provisioning/credential checks. A media failure returns 503 and rolls back session/history. Missing paths are not restored automatically. Current reports publishReady=false when a path is absent; credential issuance returns 503. Reconnect at/after its stored deadline cannot obtain credentials, even before phase 4 expiry cleanup runs.

`StreamSessionClosureService` provides the synchronous close-before-commit foundation; its HTTP endpoint, callback lifecycle handlers and reconnect scheduler remain phase 4 work. With no lifecycle handler, an actual publisher still leaves DB READY; callback returns 404. Ended sessions cannot receive credentials or be reopened.

Database rollback cannot undo MediaMTX effects. A timeout/crash/failed DB commit can leave an orphaned or missing media path; the MVP requires manual inspection and has no reconciler or automatic restore.

Additional runtime verification (isolated PostgreSQL + Stream HTTP + MediaMTX + synthetic FFmpeg publisher + Python owner smoke script):

```sh
./stream-service/mvnw -f stream-service/pom.xml -Dtest=StreamProvisioningMediaIT -Dstream.provisioning.it=true test
```

The OBS method is deliberately opt-in and is skipped by this command. [Phase 3 Test Plan](../docs/testing/Streaming%20MVP%20Phase%203%20Test%20Plan.md) provides the operator-controlled OBS command and full-stack owner smoke instructions. Neither mode opens or controls OBS. M3 awaits the user's OBS acceptance.

## Streaming phase 4: synchronous lifecycle, reconnect sweep and End

- `POST /stream/api/media/events`: Direct webhook endpoint called by MediaMTX `media_hook.py`.
  - Under pessimistic session lock, inspects real source via Control API `getSource(sessionId)`.
  - `ONLINE` event transitions `READY` or `RECONNECTING` to `LIVE`, sets `startedAt` on initial live event, and logs history.
  - `OFFLINE` event transitions `LIVE` to `RECONNECTING` with 60-second grace (`reconnectDeadline = now + 60s`). Duplicate offline calls do not extend or reset deadline.
  - Events for terminal sessions (`ENDED`) or from obsolete connections are safely ignored (`IGNORED`).
- Background reconnect sweep:
  - Enabled via `stream.lifecycle.reconnect-sweep-enabled: true`.
  - `ReconnectExpirationScheduler` polls expired reconnecting sessions every 1s (`stream.lifecycle.reconnect-sweep-delay: 1s`).
  - Calls `StreamSessionClosureService.expireReconnectIfDue`, deletes media path, kicks remaining connection, and commits `ENDED/CLOSED`.
- Synchronous End API:
  - `POST /stream/api/streams/{sessionId}/end` requires authenticated channel owner.
  - Deletes media path and kicks source before committing `ENDED/CLOSED`. Idempotent if already ended. Failures rollback DB and return 503.

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
