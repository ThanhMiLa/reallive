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

Security follows the [Auth State contract](../docs/contracts/Auth%20State.md). Gateway validates JWT signatures, lifetime, token type, revocation and account status. Stream Service reads the forwarded access-token cookie and loads current authorities from Redis on each authenticated request, ignoring JWT scope. Backend ports are restricted to trusted callers in this model. All endpoints initially require authentication; feature implementations define any public routes.

Authentication and authorization failures use `ApiResponse` with HTTP 401/403. Missing, corrupt or unavailable Redis authorities return HTTP 503 without clearing cookies. Application errors, invalid requests and database failures also use the common response envelope.

## Development

Copy `.env.example` to `.env`, set `STREAM_DB_PASSWORD` and `REDIS_PASSWORD` to match the repository-root Docker Compose environment, then export the variables or load the file through the IDE run configuration. Spring Boot does not automatically load `.env` files.

The development profile defaults to `http://localhost:8082/stream`, PostgreSQL at `localhost:5434/reallive_stream`, Redis at `localhost:6379` (database `0`) and Kafka at `localhost:9092`. Redis must match the instance and logical database used by User Service and Gateway.

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

`application-dev.yaml` includes the database, JPA/Flyway, Redis, Kafka producer, access-token cookie and downstream service settings. Flyway is enabled and Hibernate validates the schema. Add migrations under `src/main/resources/db/migration` with the first persisted feature. Add Media Server properties and lifecycle event topics when implementing their adapters.

## Validation

```shell
./mvnw clean verify
```

Tests use H2 and mocked Redis authorities without requiring local infrastructure or application credentials. They cover application startup, the security filter chain, current Redis permissions and common HTTP error responses. Flyway is disabled in the test profile until schema migrations are introduced.
