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

`expiresAt` is an `Instant` representing the OTP expiration time in UTC. The resend cooldown is enforced separately by the backend at 60 seconds; the OTP lifetime is configured separately. The send response acknowledges the request, not email delivery. The verify response uses a boolean `emailVerified`. Streamer identifiers are UUIDs, `status` uses `StreamerStatus` (successful registration returns `ACTIVE`), and `roles` contains role names such as `USER` and `STREAMER`, preserving existing roles. The DTOs do not assign roles or default streamer status before business validation.

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

| Setting | Default |
|---|---|
| `AUTH_ACCESS_TOKEN_TTL` | 900 seconds |
| `AUTH_REFRESH_TOKEN_TTL` | 2592000 seconds |
| `ACCESS_TOKEN_COOKIE_NAME` | `access_token` |
| `ACCESS_TOKEN_PATH` | `/` |
| `REFRESH_TOKEN_COOKIE_NAME` | `refresh_token` |
| `REFRESH_TOKEN_PATH` | `/user/api/auth` |

Cookie Max-Age derives directly from the corresponding JWT TTL; separate Max-Age overrides are not supported. Secure, HttpOnly, and SameSite attributes are configured through the corresponding access/refresh environment variables in `.env.example`. Use HTTPS and secure cookies when deployed.

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
