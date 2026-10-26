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

`application-dev.yaml` includes the database, JPA/Flyway, Redis, Kafka producer, access-token cookie and downstream service settings. Flyway is enabled and Hibernate validates the schema. Migrations V1–V3 under `src/main/resources/db/migration` produce the two-table session/history schema. Media authentication properties are configured; the Control API adapter is planned for phase 3.

## Validation

```shell
./mvnw clean verify
```

Tests use H2 for HTTP/application checks and isolated PostgreSQL 17 Testcontainers for Flyway, persistence and concurrency checks. Docker is required. Redis authorities are mocked; test media keys are generated in memory, so application credentials are not required. The direct Stream Service/MediaMTX check is opt-in as described below.

## Streaming phase 2: eligibility and media authentication

Phase 1 domain/persistence and phase 2 authentication are implemented. Business Create/Update/End,
playback-access APIs and the lifecycle callback handler are planned for phases 3–5.
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
docker build -f infra/mediamtx/Dockerfile -t reallive-mediamtx:1.21.2 .
./stream-service/mvnw -f stream-service/pom.xml -Dtest=MediaMtxAuthenticationIT -Dstream.media.it=true test
```

The explicit integration test starts Stream Service with H2/test keys and
one disposable MediaMTX container on random ports. MediaMTX fetches JWKS directly from Stream Service. Java-issued
JWTs are checked against real publish/read/api endpoints; invalid tokens and media JWT account-cookie
misuse are rejected. Direct callback JSON is checked against Stream Service. RTMP uses a synthetic
FFmpeg source; no OBS/browser automation is performed. Paths and container are cleaned up.
The existing application MediaMTX and databases are not reconfigured. This opt-in IT is not part of the
default Surefire test selection and must be executed before recording phase 2 runtime acceptance.
