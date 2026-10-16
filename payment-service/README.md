# Payment Service

Java 21 / Spring Boot service owning wallets and payment data.

## Wallet persistence foundation

- `common.entity.BaseEntity` maintains creation and update timestamps.
- `wallet.entity.UserWalletEntity` maps `user_wallets` from the data model.
- `wallet.repository.UserWalletRepository` provides persistence and lookup by user ID.
- Flyway migration `V1__create_user_wallets_table.sql` creates the wallet table with generated UUIDs, a zero initial balance, unique user IDs and a non-negative balance constraint.

`user_id` is an external UUID from User Service, without a cross-service foreign key or a Java entity relationship. The unique constraint creates its own index. Creating the wallet through an idempotent internal API and calling that API during registration are the next implementation steps.

## Development

The repository's Docker Compose exposes the payment PostgreSQL database at `localhost:5435` with database `reallive_payment` and username `payment_service`.

Copy `.env.example` to `.env` and configure the database credentials. Set `REDIS_PASSWORD` to the same value as the repository-root `.env` used by Docker Compose. Redis authorities must use the same host, port and logical database as User Service and Gateway. The development defaults are `localhost:6379`, database `0`.

Export those variables into the shell or configure the IDE run configuration to load this service's `.env`; Spring Boot does not load `.env` files automatically. After changing credentials, restart the service. A missing Redis password against the Compose Redis instance produces `NOAUTH HELLO`. Start the service with the development profile:

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Flyway manages the schema. Hibernate uses `ddl-auto=validate` to check entity mappings without creating tables.

## Common HTTP infrastructure

The development service listens at `http://localhost:8083/payment`, matching the Gateway's `/payment/**` route. `ACCESS_TOKEN_COOKIE_NAME` configures the cookie read by Spring Security. The common JWT decoder follows the existing trusted-Gateway model: Gateway verifies tokens and revocation, and Payment reads the forwarded token claims for authentication and method authorities. Backend access must follow that deployment model.

`JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` return the standard JSON envelope for HTTP 401 and 403. `GlobalExceptionHandler` maps application errors and database failures to that envelope; database failures return `PAYMENT_DATA_UNAVAILABLE` (1003 / HTTP 503) without exposing SQL or connection details.

## Validation

```shell
./mvnw clean verify
```

Tests use an isolated H2 database in PostgreSQL compatibility mode. Repository tests verify the migration, JPA mapping, defaults, required fields, balance updates and uniqueness under concurrent inserts. They can also run against a disposable PostgreSQL database using `wallet.test.database-url`, `wallet.test.database-username` and `wallet.test.database-password` system properties; each run creates and drops its own schema.

Common tests cover cookie selection, JWT parsing, scope-to-authority mapping, the real Spring Security filter chain, method authorization and HTTP error envelopes. The application context test also initializes the servlet security configuration.
