# User Service

## Login

`POST /api/auth/login` accepts a username and password:

```json
{
  "username": "alice",
  "password": "your-password"
}
```

Usernames are trimmed and compared without case sensitivity. Passwords are
used unchanged and must fit BCrypt's 72-byte UTF-8 limit. Login searches only
the username column; an account's email cannot be used in place of its username.

An active account can log in before verifying its email. A banned account with
the correct password receives `403`. Unknown accounts and wrong passwords both
receive the same `401` response. Invalid request bodies receive `400`.

On success, the JSON body contains `userId`, `username`, `email`, `emailVerified`
and `roles`. Tokens and password hashes are never included in the response body.
The response sets two host-only cookies:

| Cookie | HttpOnly | Path | Default lifetime |
| --- | --- | --- | --- |
| `access_token` | Yes | `/` | 86400 seconds (1 day) |
| `refresh_token` | Yes | `/api/auth` | 86400 seconds (1 day) |

`TokenPair` contains only the two token strings. `AuthCookieWriter` reads each
cookie's name, max-age, secure, http-only, same-site and path through `@Value`.
Cookie max-age is configured independently of JWT expiration. The JWT remains
valid for its configured token TTL even if its cookie has a different lifetime.

Both tokens are HS512-signed JWTs. They contain `sub`, `jti`, `iss`, `aud`,
`iat`, `exp`, `type` and `roles`. The `type` claim is `access` or `refresh`.
Issuer is fixed to `reallive-user-service` and audience to `reallive-api` in the
JWT adapter. The two tokens have different `jti` values. Login does not persist
session records. Refresh, logout, registration and access-cookie authentication
for protected endpoints are not implemented yet.

## Cookie and CSRF flow

1. Call `GET /api/auth/csrf` with credentials enabled. It returns `204` and sets
   the readable `XSRF-TOKEN` cookie.
2. Read that cookie and send its value in `X-XSRF-TOKEN` when calling login.
3. Keep credentials enabled so the browser stores the access and refresh cookies.
4. Login rotates `XSRF-TOKEN`; read its current value before subsequent requests.

Missing or mismatched CSRF tokens receive `403`, including on login. The service
does not use HTTP sessions. Login and CSRF responses use `Cache-Control: no-store`.

```javascript
const api = 'http://localhost:8080';

await fetch(`${api}/api/auth/csrf`, { credentials: 'include' });
const csrf = document.cookie.split('; ')
  .find(cookie => cookie.startsWith('XSRF-TOKEN='))?.split('=')[1];

const response = await fetch(`${api}/api/auth/login`, {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    'X-XSRF-TOKEN': decodeURIComponent(csrf ?? ''),
  },
  body: JSON.stringify({ username: 'alice', password: 'your-password' }),
});
```

This example assumes the web app and API share the `localhost` hostname. For
frontend and API hosts that cannot share readable cookies, expose the CSRF
bootstrap through the gateway on the frontend's API origin.

## Configuration and local startup

Configuration lives in `src/main/resources/application.yaml`. Export environment
variables to the service process or configure them in the IDE; Spring does not
automatically read the root `.env` used by Docker Compose.

`JwtTokenProviderAdapter` uses `@Value` and Lombok `@NonFinal` on configuration
fields. When issuing tokens, it calls `generateToken(user, isRefreshToken)`
for each token. That method builds the claims and signs the JWT with the shared
secret. Token expiry is calculated directly from TTL seconds.
Cookie construction belongs to `presentation/rest/cookie/AuthCookieWriter`.
Cookie and CORS settings are read through `@Value` by their HTTP components.

| Variable | Purpose / default |
| --- | --- |
| `USER_DB_URL` | `jdbc:postgresql://localhost:5432/reallive_user` |
| `USER_DB_USERNAME` | `user_service` |
| `USER_DB_PASSWORD` | Required; match Docker Compose |
| `JWT_SIGNER_KEY` | Required random shared secret, at least 64 bytes; used as UTF-8 text |
| `AUTH_ACCESS_TOKEN_TTL` | `900` seconds (15 minutes) |
| `AUTH_REFRESH_TOKEN_TTL` | `2592000` seconds (30 days) |
| `AUTH_ALLOWED_ORIGINS` | `http://localhost:5173`; comma-separated explicit origins |

Token TTL values must be positive integers in seconds. Duration strings such as
`15m` or `30d` are not accepted.

Each auth cookie has a separate configuration group:

| YAML group | Setting | Environment variable | Default |
| --- | --- | --- | --- |
| `auth.cookie.access-token` | `name` | `ACCESS_TOKEN_COOKIE_NAME` | `access_token` |
| | `max-age` | `ACCESS_TOKEN_MAX_AGE` | `86400` seconds |
| | `secure` | `ACCESS_TOKEN_SECURE` | `false` for local HTTP |
| | `http-only` | `ACCESS_TOKEN_HTTP_ONLY` | `true` |
| | `same-site` | `ACCESS_TOKEN_SAME_SITE` | `Lax` |
| | `path` | `ACCESS_TOKEN_PATH` | `/` |
| `auth.cookie.refresh-token` | `name` | `REFRESH_TOKEN_COOKIE_NAME` | `refresh_token` |
| | `max-age` | `REFRESH_TOKEN_MAX_AGE` | `86400` seconds |
| | `secure` | `REFRESH_TOKEN_SECURE` | `false` for local HTTP |
| | `http-only` | `REFRESH_TOKEN_HTTP_ONLY` | `true` |
| | `same-site` | `REFRESH_TOKEN_SAME_SITE` | `Lax` |
| | `path` | `REFRESH_TOKEN_PATH` | `/api/auth` |

Cookie max-age is in seconds. Set secure to `true` for HTTPS; `SameSite=None`
requires secure cookies. The refresh path matches this service's `/api/auth`
endpoints. The readable CSRF cookie uses the access cookie's secure and same-site
settings, with path `/` and HttpOnly disabled.

Use a stable signing secret across restarts and instances. Generate a local secret once
and store its value in your local environment configuration:

```sh
export JWT_SIGNER_KEY="$(openssl rand -base64 64 | tr -d '\n')"
```

From the repository root, start PostgreSQL:

```sh
docker compose up -d --wait postgres-user
```

Then run inside `user-service`, with the environment variables configured:

```sh
./mvnw spring-boot:run
```

Flyway runs `db/migration/V1__create_user_authentication_tables.sql` in an empty
database to create the eight tables in Data Model sections 1.1.1–1.1.8: `users`,
`user_google_accounts`, `roles`, `user_roles`, `permissions`, `role_permissions`,
`invalidated_tokens` and `profiles`. Hibernate then validates the schema.
No accounts, roles or permissions are seeded. The application supplies UUIDs and
maintains `updated_at` when updating records; timestamp defaults apply on insert.
For manual login testing, use an existing account with a BCrypt password hash and
a role assignment. Databases with an existing unmanaged schema need a deliberate
Flyway baseline; automatic baselining is not enabled.

CORS currently allows direct frontend access to user-service for development.
Move browser-facing CORS to API Gateway when external requests go through it.
All routes except login and CSRF bootstrap are denied until their authentication
and authorization rules are implemented.

## Architecture and tests

The `auth` feature follows the repository's
[Clean Architecture skill](../.agents/skills/clean-architecture/SKILL.md):

- `domain`: plain Java account model, status and business exceptions.
- `application`: login input/output ports, shared DTOs and orchestration.
- `infrastructure`: JPA entities/repository/mapper/adapter, password verification and JWT signing.
- `presentation`: controllers validate shared request DTOs and deliver cookies.
- `common`: shared BCrypt `PasswordEncoder` configuration under
  `infrastructure/configuration`, service-wide security and HTTP exception mapping.

Request and response DTOs live only under application:

```text
auth/application/dto/
├── request/
│   └── LoginRequest.java
└── response/
    ├── LoginResponse.java
    └── TokenPair.java
```

`LoginService` and `AuthController` use the same `LoginRequest` and `LoginResponse`.
The response's internal `tokens` field is marked `@JsonIgnore`; the controller
uses it to set cookies, and it is excluded from JSON serialization.

Run `./mvnw clean verify` inside this module. Tests use generated RSA keys and an
in-memory H2 database in PostgreSQL mode, execute the Flyway migration, and cover
login decisions, real CSRF cookie/header exchange, CORS, cookie flags and JWT
signature/claims. They do not depend on local credentials or running containers.
