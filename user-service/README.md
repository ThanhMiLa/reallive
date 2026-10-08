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

To include the real Redis concurrent-refresh test, use an isolated localhost Redis without a password:

```bash
./mvnw clean verify -Dgateway.test.redis-port=6379
```

That optional test uses database 4 and deletes its randomly generated key afterward. Do not point it at the application's Redis.
