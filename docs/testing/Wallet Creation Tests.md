# Wallet Creation Tests

The wallet registration tests cover the current synchronous User Service → Payment Service flow. Production code is unchanged by this test work. Internal wallet creation intentionally accepts requests without user cookies; deployment network restrictions and the Gateway route boundary protect that endpoint.

## Test scope

| Layer | Cases |
| --- | --- |
| User registration unit tests | Username/email conflicts, password mismatch, missing USER role, user/profile persistence failures, saved user ID passed once to Payment before login, application errors, Feign HTTP errors, read timeout, missing response/body/result, login failure after wallet creation. |
| User registration integration tests | Actual register controller, Spring Security, transactional JPA repositories, Flyway and the real Feign proxy against an HTTP stub. Successful registration without cookies, auth cookies returned only after wallet creation, rollback on 401/409/500/503, malformed/empty response, remote commit followed by lost response, immediate retry with the same username, and rejected input without a Payment call. |
| Feign transport integration tests | Configured URL including `/payment`, POST method and UUID path, generic response decoding, no user token requirement, HTTP 400/401/403/404/409/500/503, invalid JSON, empty body, read timeout without automatic retry, and refused connection. |
| Payment unit tests | New wallet starts at zero, persistence happens before mapping, existing wallet conflict, lookup/save/mapping failures, mapper null input, identity and nonzero balance mapping, entity equality and hash stability. |
| Payment controller tests | Anonymous internal POST, response envelope, malformed UUID, duplicate conflict, sanitized database error, protected GET/other internal paths/public paths, and invalid access cookie rejection. |
| Payment database integration tests | Controller through security and transactional service to persistence, defaults and timestamps, repeated requests preserve wallet identity and coins, different users get different wallets, null user ID rejection, and concurrent HTTP requests commit exactly one wallet. Existing repository tests verify unique/not-null/balance constraints, Flyway reruns and concurrent inserts. |
| Gateway integration tests | Use the route predicates from `application-dev.yml`. Internal wallet creation and legacy/similar paths never reach the backend, with or without an access cookie. Public Payment paths still route and require authentication. |

User-side HTTP stubs simulate a separate Payment transaction, including a committed wallet whose response is lost. They do not connect User Service directly to Payment's database. Payment persistence and concurrency are tested separately against the real Payment application and database.

## Coverage

Both services provide the Maven profile `wallet-creation-coverage` with JaCoCo reports at `target/site/jacoco-wallet/index.html` and `jacoco.xml`.

- User Service enforces 100% line and branch coverage for `AuthServiceImpl.register`, `createWallet` and `buildProfileEntity`. Other authentication flows retain their existing tests but are outside this feature's coverage gate.
- Payment Service enforces 100% line and branch coverage across the `wallet` package. Lombok-generated methods are automatically filtered by JaCoCo; MapStruct's mapping implementation is included.
- Exception handlers are tested through observable behavior and executed catch lines. JaCoCo does not count exception paths as branch decisions.

The tests do not use sleeps to orchestrate wallet concurrency or lost responses. Latches coordinate concurrent requests; the Feign read-timeout tests use a 300 ms timeout and a server response latch. HTTP test servers bind random loopback ports and close after the suite.

## Run locally

Inside `payment-service`:

```sh
./mvnw clean verify -Pwallet-creation-coverage
```

Inside `user-service`:

```sh
./mvnw clean verify -Pbecome-streamer-coverage,wallet-creation-coverage
```

Inside `api-gateway`:

```sh
./mvnw clean verify
```

These commands use H2 in PostgreSQL mode for database tests. User/Gateway Redis tests require the existing `user.test.redis-port` and `gateway.test.redis-port` system properties; without them the Redis-dependent tests are skipped.

To validate actual PostgreSQL behavior, provide `wallet.test.database-url`, `wallet.test.database-username`, and `wallet.test.database-password` for Payment; `registration.test.database-url`, `registration.test.database-username`, and `registration.test.database-password` for registration. Streamer tests retain their existing `streamer.test.database-*` properties. The PostgreSQL test user must be allowed to create and drop test schemas. Registration and Payment integration tests create random schemas and delete them afterward.

The GitHub workflows supply PostgreSQL 17 for registration/Payment persistence, Redis for the existing User/Gateway tests, enable the coverage gates, and upload test/coverage artifacts. No Redis or Kafka dependency is added to Payment Service.

## Current behavior and known gaps

The suite makes the following existing behavior explicit rather than presenting it as completed acceptance criteria:

1. Payment rejects repeated wallet creation with `WALLET_ALREADY_EXISTS` / HTTP 409. It preserves the original wallet and balance, but does not return the existing wallet as a successful idempotent response.
2. A concurrent insert race cannot create two wallets. Depending on whether the second existence check sees the committed wallet, the losing request currently returns 409 or the database-error envelope with HTTP 503.
3. `AuthServiceImpl.createWallet` catches `AppException`, while the default Feign client throws `FeignException`/`RetryableException`. Real HTTP and transport failures roll back the local transaction but are not translated into `PAYMENT_SERVICE_UNAVAILABLE` by that catch block.
4. Successful HTTP replies with no response/body/result currently fail with `NullPointerException` in registration and roll back the local transaction.
5. A remote wallet committed before a lost response can remain orphaned after the user transaction rolls back. This is the accepted flow discussed for this feature; automatic cleanup is not implemented here.
