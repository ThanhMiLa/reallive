# User Service

Java 21 / Spring Boot service for accounts and authentication. Development requests reach this service through API Gateway on port `8888`; user-service listens on `8080` with context path `/user`.

## Authentication APIs

| Method and external path | Behavior |
|---|---|
| `POST /user/api/auth/login` | Checks username/password and active status, issues access/refresh cookies |
| `POST /user/api/auth/register` | Creates an account and issues access/refresh cookies |
| `POST /user/api/auth/refresh` | Consumes the refresh token once and issues new cookies for an active account |
| `POST /user/api/auth/logout` | Revokes supplied tokens and expires both cookies |

Login accepts `{username, password}`. Register accepts `{username, email, password, confirmPassword}`. Refresh/logout read token cookies, not JSON token fields or Authorization headers. Tokens are removed from the JSON response after being written to `Set-Cookie`.

## Personal profile API

`GET /user/api/users/me` returns the authenticated user's profile using the `access_token` cookie. The user ID comes from the JWT subject; no user ID parameter is required.

The `result` object contains `userId`, `displayName`, `email`, `avatarUrl`, `dob`, and `create_at`. `create_at` is the account creation timestamp from `users.created_at`. `avatarUrl` and `dob` may be `null`. Dates use `YYYY-MM-DD`, and creation timestamps use ISO local date-time format.

The API returns 401 for unauthenticated requests, 404 when the profile is missing, and 403 when the account is inactive.

`PATCH /user/api/users/me` updates the current user's profile with `multipart/form-data`:

| Field | Rules |
|---|---|
| `displayName` | Optional; trimmed, nonblank, at most 100 characters |
| `dob` | Optional; strict `yyyy-MM-dd`, not in the future |
| `avatar` | Optional file; JPEG, PNG or WebP, at most 5 MiB and 16 million pixels |

At least one field must be supplied. Omitted fields remain unchanged; blank values and empty uploaded files are rejected. This endpoint does not clear existing fields. User identity comes from the access cookie, and fields such as `email`, `userId`, `avatarUrl`, and `create_at` cannot be changed through this request. The response uses the same six profile fields as GET. The server detects the image format from its content, ignoring the supplied filename and MIME type.

Standard input constraints are declared on `UpdateProfileRequest` and triggered by `@Valid` in the controller. Date of birth binds to `LocalDate` and uses `@PastOrPresent`; display names are trimmed during form binding before `@Size` and `@Pattern` validation. The DTO contains only the three request fields and uses Lombok for accessors and construction. The service rejects updates without any fields and uses `AvatarValidator` to check image content before uploading. It also handles account status, profile persistence and storage transaction cleanup.

The development configuration reads `BUCKET_NAME`, `PUBLIC_BASE_URL`, `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY` from the environment through YAML placeholders and `@Value`. Use the account's S3-compatible R2 endpoint without the bucket suffix. `PUBLIC_BASE_URL` is the public bucket URL (`r2.dev` for development, a custom domain for production). Configure the R2 token with Object Read & Write access to the avatar bucket. Only the backend uploads to R2; no browser-to-R2 upload CORS configuration is needed.

```bash
curl -X PATCH http://localhost:8888/user/api/users/me \
  -b cookies.txt \
  -F 'displayName=New Viewer' \
  -F 'dob=2000-01-01' \
  -F 'avatar=@/path/to/avatar.png'
```

When gateway CSRF protection is enabled, initialize its CSRF cookie and include the corresponding `X-XSRF-TOKEN` header, as described in the gateway README.

Images use unique keys under `avatars/<userId>/<uuid>.<extension>`; the full public URL is stored in `profiles.avatar_url`. Updates lock the profile row until the database transaction completes. Upload failures return 502 and roll back profile changes; transaction rollback attempts to delete the new upload. After commit, the previous avatar is deleted only if its URL belongs to the configured public base URL and the same user's generated avatar path. Cleanup failures are logged for manual retry and do not fail a committed update. Changing the public base URL requires updating existing stored URLs separately.

Invalid updates return 400 and oversized uploads return 413. The development servlet limits individual files to 5 MiB and the total multipart request to 6 MiB.

## Email verification and streamer DTO contracts

The DTOs below define the planned API contracts. Controllers and business logic for these endpoints are not implemented yet. Paths include the existing `/user` context path used through API Gateway.

| Method and external path | Request | Successful `ApiResponse.result` |
|---|---|---|
| `POST /user/api/users/me/email-verification/otp` | No request body | `EmailVerificationOtpResponse`: `expiresAt` |
| `POST /user/api/users/me/email-verification/verify` | `VerifyEmailOtpRequest`: `{"otp":"012345"}` | `EmailVerificationResponse`: `emailVerified` |
| `POST /user/api/users/me/become-streamer` | No request body | `BecomeStreamerResponse`: `streamerId`, `userId`, `status`, `roles` |

All three endpoints require authentication. User identity comes from the security context, and the recipient email comes from the database. No request DTO accepts a target user ID or recipient email. OTP validation requires exactly six ASCII digits as a string, preserving leading zeroes; missing or malformed values map to `OTP_INVALID` (`400 / 1019`). OTP values are excluded from the request DTO's generated `toString()`.

`expiresAt` is an `Instant` representing the OTP expiration time in UTC. OTP validity and resend cooldown share one 60-second TTL. The send response acknowledges the request, not email delivery. The verify response uses a boolean `emailVerified`. Streamer identifiers are UUIDs, `status` uses `StreamerStatus` (successful registration returns `ACTIVE`), and `roles` contains role names such as `USER` and `STREAMER`, preserving existing roles. The DTOs do not assign roles or default streamer status before business validation.

## Email verification and streamer service structure

Email verification belongs to `user`; streamer registration belongs to `streamer`. Both service contracts receive a user UUID extracted from the authenticated principal. EmailVerificationController exposes OTP requests; verification and streamer controllers remain scheduled for later steps.

| Contract / class | Responsibility |
|---|---|
| `user.service.EmailVerificationService` | Request OTP and verify OTP using the existing request/response DTOs |
| `user.service.impl.EmailVerificationServiceImpl` | Coordinate user persistence, OTP storage and email publishing |
| `user.adapter.redis.EmailVerificationOtpStore` | Check cooldown, save a hash only if absent, consume OTP atomically and clean up only a matching hash |
| `user.adapter.kafka.EmailVerificationEmailPublisher` | Publish an email request and wait for broker acknowledgement, independently of email delivery |
| `streamer.service.StreamerRegistrationService` | Become Streamer for the authenticated user |
| `streamer.service.impl.StreamerRegistrationServiceImpl` | Coordinate database eligibility checks and atomic streamer/role persistence |

`user.dto.response` holds cooldown and verification results. The publisher accepts `user.dto.message.EmailVerificationRequestedMessage` directly, following the [Kafka wire contract](../docs/contracts/Email%20Verification%20Kafka.md). Raw OTPs are excluded from generated `toString()` methods. The Redis implementation is in `user.adapter.redis`; `KafkaEmailVerificationEmailPublisher` is in `user.adapter.kafka`.

## Redis OTP storage

`RedisEmailVerificationOtpStore` stores one String key, `otp:email-verification:cooldown:{userId}`, whose value is the OTP HMAC. `email-verification.otp.ttl-seconds` defaults to 60 seconds and reads `OTP_COOLDOWN_SECONDS` in the dev profile. This is the only lifetime setting: the old separate OTP TTL and attempt-limit settings are no longer used.

`hasCooldown` allows an early rejection before OTP generation. `save` uses a Lua script with SET NX EX and reports the remaining TTL on rejection, without replacing the hash or extending expiry. `consume` atomically compares and deletes the matching hash; a mismatch preserves the key, and a missing key returns NOT_FOUND. `deleteIfCurrent` uses the same comparison for cleanup. Wrong-OTP attempts are not limited in this version.

`EmailVerificationOtpHasher` computes HMAC-SHA256 from the verification purpose, user ID, database email and six-digit OTP. It uses `OTP_HMAC_KEY` independently of the JWT key; the key is required when hashing is invoked, with no additional startup validation configuration. The hash and OTP are not logged. User and email binding prevents using a code for a different user or database email.

The storage, hashing, email verification service and Kafka publisher are Spring beans. The OTP request endpoint is implemented; OTP verification and transactional `emailVerified` updates remain in step 8.

To run the Redis integration tests against an isolated instance, start Redis from the repository root:

```bash
docker run --rm -d --name reallive-otp-redis-test -p 127.0.0.1:16380:6379 redis:7.4-alpine
```

Then run from `user-service/`:

```bash
./mvnw -o -Dtest=RedisEmailVerificationOtpStoreTest,EmailVerificationOtpHasherTest -Duser.test.redis-port=16380 test
```

Finally stop the test container:

```bash
docker stop reallive-otp-redis-test
```

The Redis tests use database 15, unique user IDs and delete only their own keys. They cover the 60-second TTL, automatic expiry (using a shorter test-only lifetime), resend rejection, unlimited wrong attempts without TTL extension, immediate consume, cleanup and concurrent saves/consumes. Without `user.test.redis-port`, Redis-dependent tests are skipped.

## Request an email verification OTP

`POST /user/api/users/me/email-verification/otp` requires the existing access-token cookie and no request body. The controller uses the authenticated subject for identity; the service queries the current database account status, email and verification flag. An ACTIVE account with an unverified email can request an OTP.

The service checks `hasCooldown`, generates six digits with SecureRandom, computes the bound HMAC and saves it with SET NX and the shared 60-second TTL. Only the request that saves the hash publishes `EmailVerificationRequestedMessage` to Kafka, keyed by user ID. The publisher waits up to 20 seconds for acknowledgement, in addition to the configured producer send/metadata blocking limit. HTTP 202 means the broker accepted the request; it does not report email delivery.

Successful response:

```json
{
  "status": 202,
  "code": 1000,
  "message": "Email verification request accepted",
  "result": { "expiresAt": "2026-10-09T08:01:00Z" },
  "timestamp": "2026-10-09T08:00:00Z"
}
```

| Condition | HTTP / code |
|---|---|
| Missing/invalid authentication or subject | 401 / 1001 |
| User not found | 404 / 1004 |
| Account not ACTIVE | 403 / 1003 |
| Email already verified | 409 / 1020 |
| Cooldown or lost concurrent save | 429 / 1021 |
| Redis/Kafka request failure | 503 / 1022 |
| Missing HMAC key | 500 / 1023 |

Cooldown uses the existing error envelope without a countdown DTO or Retry-After header. The frontend starts its own 60-second countdown after acceptance. Rejected requests do not replace a hash or extend TTL. The endpoint never updates emailVerified, roles or streamer records.

A failure before Kafka send, such as JSON serialization, withdraws only the matching Redis hash. A timeout or other uncertain send failure retains the key and returns 503. Cleanup failure never changes the result into success. Redis and Kafka are not a shared transaction; a crash between save and publication can require waiting for TTL expiry before retrying.

The producer error listener omits message contents, and the dev profile keeps KafkaTemplate logging at INFO to prevent its debug/trace payload logs. Do not enable payload logging for OTP messages.

Service, controller and publisher tests cover eligibility, database values over stale JWT claims, cooldown, leading zeros, acknowledgement, error cleanup and uncertain outcomes. `EmailVerificationOtpIntegrationTest` connects the HTTP endpoint to real Redis and an embedded Kafka broker, including simultaneous requests that produce one message. It is enabled by `-Duser.test.redis-port=...`; it never calls SendGrid. To run the complete suite including the existing refresh-token Redis test against the isolated instance:

```bash
./mvnw -o -Duser.test.redis-port=16380 -Dgateway.test.redis-port=16380 clean verify
```

`verifyEmailOtp` and StreamerRegistrationServiceImpl remain scaffolds. Their endpoints and business logic are implemented in later steps.

## Streamer persistence

V4 preserves the historical profiles migration with checksum `-843705742`. Databases that previously applied `V4__create_profiles_table.sql` must resolve that same migration; the streamer table uses V6 so the applied V4 version is not reused.

Flyway V6 adds `streamers` with a unique, required `user_id` referencing `users`, status validation (`PENDING`, `ACTIVE`, `SUSPENDED`, `BANNED`), verification and suspension timestamps, and a suspension reason. `StreamerEntity` and `StreamerRepository.findByUserId` expose the record independently of the user's roles, including bans that remain after role removal. Status must be supplied explicitly when saving a streamer.

V5 seeds the `STREAMER` role if absent, preserving any existing role ID, permissions and memberships. It does not grant roles to users or introduce new permissions. Streamer registration will add this role alongside existing roles through `user_roles`; account registration now initializes a mutable role set.

`UserRepository.findByIdForUpdate` acquires `PESSIMISTIC_WRITE` on the user row. The registration service must call it inside the transaction before reading eligibility, then read roles and the streamer record in that same transaction. The lock lasts until commit or rollback; requests for the same user wait and recheck committed database state. Different users remain independent. The unique constraint additionally prevents duplicate streamer records at the database level. The registration service and API business logic remain scheduled for a later step.

## Gateway trust and business authorization

API Gateway verifies every supplied access/refresh cookie, including on public auth endpoints. It checks HS512 signatures, issuer, claims, expiration, token type, and Redis blacklist entries. It rejects client Authorization headers. See [the gateway contract](../api-gateway/README.md) for CORS/CSRF, cookie recovery, error codes, and client examples.

`CustomJwtDecoder` deliberately parses the forwarded access JWT without rechecking its signature or blacklist. Spring Security reads the configured access cookie, constructs the principal, and maps `scope` entries directly to authorities such as `ROLE_USER` and `USER_READ`. Business services can apply method-level authorization and ownership checks to that identity.

Only trusted callers may reach backend ports. The gateway trust model requires private networking or equivalent caller restrictions; this repository change does not provision them.

## Tokens and Redis

Both JWTs use HS512 with the UTF-8 bytes of `JWT_SIGNER_KEY`, issuer `reallive.com`, and claims `sub`, `iat`, `exp`, `jti`, `scope`, and `type`. Each token has its own `jti`; `type` is `ACCESS` or `REFRESH`.

User-service writes string keys with a TTL equal to the remaining token lifetime:

```text
auth:revoked:access:{jti}
auth:revoked:refresh:{jti}
```

Refresh consumption uses `SET NX` with TTL so concurrent refresh requests cannot both issue tokens. The gateway's blacklist read is only an early check; the atomic consume remains in this service. An expired refresh token cannot be consumed, including when it expires after gateway verification. Refresh rechecks account status before issuing new tokens.

## Configuration and run

Activate `dev` and configure PostgreSQL, Redis, and a shared random `JWT_SIGNER_KEY` of at least 64 UTF-8 bytes. Gateway and user-service must share the key, Redis database, and cookie attributes. The key is used as text rather than Base64-decoded.

Kafka and OTP settings are in `application-dev.yaml`; activate the `dev` profile when running locally. User Service does not add a separate startup validator for these settings. Use an independent `OTP_HMAC_KEY` for OTP hashing. Tests use independent `application-test.yaml` settings. See [email verification local setup](../docs/development/Email%20Verification%20Local%20Setup.md) for environment variables, host/Docker addresses and startup order. OTP lifetime and cooldown share a 60-second TTL; wrong-attempt limits are deferred.

| Setting | Default |
|---|---|
| `AUTH_ACCESS_TOKEN_TTL` | 900 seconds |
| `AUTH_REFRESH_TOKEN_TTL` | 2592000 seconds |
| `ACCESS_TOKEN_COOKIE_NAME` | `access_token` |
| `ACCESS_TOKEN_PATH` | `/` |
| `REFRESH_TOKEN_COOKIE_NAME` | `refresh_token` |
| `REFRESH_TOKEN_PATH` | `/user/api/auth` |

Cookie Max-Age derives directly from the corresponding JWT TTL; separate Max-Age overrides are not supported. Configure Secure, HttpOnly, and SameSite through the corresponding access/refresh environment variables in `user-service/.env`. The root `.env.example` contains Docker Compose infrastructure settings only. Use HTTPS and secure cookies when deployed.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Flyway manages the database schema. Registration expects the default `USER` role to exist. Browser clients use credentials and initialize CSRF at the gateway before calling unsafe methods.

## Tests

```bash
./mvnw clean verify
```

Profile tests are organized by production class: `ProfileControllerTest`, `ProfileServiceImplTest`, `UpdateProfileRequestTest`, `ProfileMapperTest`, `AvatarValidatorTest`, `R2StorageServiceImplTest`, and `R2ConfigTest`. They cover partial updates, ownership, DTO validation, JPEG/PNG/WebP uploads, first-avatar and repeated-avatar updates, storage errors, database rollback, cleanup after commit, and concurrent updates.

Controller tests use H2 and mocked storage, with real localhost HTTP requests for multipart parsing and the file/request size limits. The test server permits swallowing up to 10 MiB after rejecting a request so the client can receive the 413 response. R2 configuration tests use a local HTTP stub and dummy credentials; the suite does not upload to Cloudflare or require real R2 credentials.

To include the real Redis concurrent-refresh test, use an isolated localhost Redis without a password:

```bash
./mvnw clean verify -Dgateway.test.redis-port=6379
```

That optional test uses database 4 and deletes its randomly generated key afterward. Do not point it at the application's Redis.

`StreamerMigrationTest` and `StreamerRepositoryTest` run against H2 by default. They cover fresh and V3 upgrades, preserving preconfigured roles, entity validation, moderation fields, database constraints, role rollback and concurrent user locking. To run these tests against an isolated PostgreSQL database instead:

```bash
./mvnw -Dtest=StreamerMigrationTest,StreamerRepositoryTest \
  -Dstreamer.test.database-url=jdbc:postgresql://localhost:55439/streamer_step3_test \
  -Dstreamer.test.database-username=streamer_test test
```

An optional `streamer.test.database-password` property supplies the test password. These tests create and drop randomly named schemas they own. Use a dedicated test database and credentials with schema creation permission.
