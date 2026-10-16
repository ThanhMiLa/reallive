# API Gateway

Spring Cloud Gateway WebFlux, Java 21. The development profile listens on port `8888` and forwards `/user/**` unchanged to user-service on port `8080`. Routing is configured under `spring.cloud.gateway.server.webflux.routes`.

## Cookie authentication contract

`AuthenticationGlobalFilter implements GlobalFilter, Ordered` authenticates access/refresh cookies supplied on routed requests, including public endpoints. The SendGrid webhook POST is in `PUBLIC_ENDPOINTS_POST`; SendGrid sends no user cookies, and Notification authenticates the provider signature. Cookie names and deletion attributes are configurable and must match user-service.

| Request | Required credentials |
|---|---|
| `POST /user/api/auth/login` | None; all supplied token cookies must be valid |
| `POST /user/api/auth/register` | None; all supplied token cookies must be valid |
| `POST /user/api/auth/logout` | None; all supplied token cookies must be valid |
| `POST /user/api/auth/refresh` | None at gateway; user-service requires a refresh cookie. All supplied token cookies must be valid |
| `POST /notification/api/webhooks/sendgrid` | SendGrid signature headers, verified by Notification; no user cookies or CSRF token |
| Other routed endpoints, including `GET /channel/api/channels` | Valid access cookie; any supplied refresh cookie must also be valid; Redis account status must be `ACTIVE` |
| `GET /api/auth/csrf` (local controller) | None; all supplied token cookies must be valid |

The filter verifies supplied cookies first, then looks up the request method and exact path in the static final `PUBLIC_ENDPOINTS` map. Add public routed paths to `PUBLIC_ENDPOINTS_GET` or `PUBLIC_ENDPOINTS_POST` in `AuthenticationGlobalFilter`; all unlisted method/path combinations require an access cookie. Refresh-cookie presence is enforced by user-service, which rejects a missing or blank refresh token.

Only token cookies are supported. A client `Authorization` header is rejected with HTTP `400`, even when valid cookies are also present. Blank or duplicate auth cookies are rejected. If both token types are supplied, their subjects must match. Unrelated cookies are ignored by authentication.

The verifier pins HS512, checks the `reallive.com` issuer, UUID subject, nonblank `jti`, `iat`, `exp`, optional `nbf`, and expected `ACCESS`/`REFRESH` type. Expiration has no grace period because blacklist entries currently expire at `exp`. JWT verification completes before that token's Redis lookup.

Redis uses the same database and string keys as user-service:

```text
auth:revoked:access:{jti}
auth:revoked:refresh:{jti}
auth:status:{userId}
```

The gateway only reads these keys. After checking token cookies and matching their subjects, protected routed requests read account status once. `BANNED` returns 403; a missing/invalid status or Redis failure returns 503. Public endpoints skip status lookup. User-service atomically consumes refresh tokens together with auth-state publication in Redis. A gateway blacklist read alone does not prevent concurrent refresh. Backend services obtain identity from the forwarded JWT and load current authorities from `auth:authorities:{userId}` on every protected request. JWT scope is ignored. See the [Auth State contract](../docs/contracts/Auth%20State.md) for ownership, TTL, synchronization and migration.

## Failures and recovery

Responses use `{status, code, message, result, timestamp}`.

| HTTP / code | Meaning |
|---|---|
| `401 / 1001` | Missing required token, invalid/expired/revoked token, duplicate cookie, or mixed subjects |
| `403 / 1002` | Missing/invalid CSRF token or access denied |
| `403 / 1003` | Account status is `BANNED` |
| `400 / 1012` | Unsupported Authorization header |
| `503 / 1013` | Redis error, lookup timeout, missing or invalid account status |

For invalid auth cookies, the gateway rejects the request and expires the affected cookies with `Max-Age=0`, matching their configured paths. Both are cleared for mixed subjects. Missing credentials, unsupported headers, and Redis failures do not clear otherwise valid cookies. Redis failures never forward the request.

JWT TTL and cookie Max-Age share the same configuration in user-service: `AUTH_ACCESS_TOKEN_TTL` (development default 86400 seconds) and `AUTH_REFRESH_TOKEN_TTL` (development default 604800 seconds). An explicitly supplied expired access cookie still blocks refresh. The client can retry refresh after the gateway has expired the bad access cookie; it must not blindly retry every `401` or repeatedly refresh with an invalid refresh token. Serialize refresh operations in the client.

## Browser and mobile clients

CORS allows the origins in `AUTH_ALLOWED_ORIGINS` (default `http://localhost:5173`) with credentials. Valid preflight requests run before authentication. CSRF protection is enabled by default for unsafe methods, including login/register/refresh/logout. The `dev` profile sets `gateway.security.csrf-enabled: false`, so local requests do not need a CSRF cookie/header or a bootstrap call. JWT cookie verification still applies. The CSRF endpoint is registered only when CSRF protection is enabled.

When CSRF is enabled, first call `GET /api/auth/csrf` with credentials enabled. It sets the HttpOnly `XSRF-TOKEN` cookie and returns `result.headerName` and a masked `result.token`. Send that returned token in the returned header on subsequent unsafe requests. The bootstrap response is not cached. Native clients must retain the CSRF cookie and send the header too.

```javascript
const gateway = 'http://localhost:8888';
const bootstrap = await fetch(`${gateway}/api/auth/csrf`, {
  credentials: 'include',
});
if (!bootstrap.ok) throw new Error('Could not initialize CSRF');
const { result: csrf } = await bootstrap.json();

const response = await fetch(`${gateway}/user/api/auth/login`, {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    [csrf.headerName]: csrf.token,
  },
  body: JSON.stringify({ username, password }),
});
```

The gateway preserves the backend's individual `Set-Cookie` headers. Access cookies default to `/`; refresh cookies default to `/user/api/auth`. Configure matching cookie attributes on both services; use secure cookies with HTTPS in deployment.

## Run and configuration

Gateway loads `.env` from its working directory using `springboot4-dotenv`, matching user-service. When running from `api-gateway/`, put the shared `JWT_SIGNER_KEY` and matching Redis/cookie settings in `api-gateway/.env`, or export them as environment variables. Set the IDE working directory to `api-gateway/` when using this file. The key must match user-service and contain at least 64 UTF-8 bytes; it is used as literal text, not Base64-decoded. No key is generated by the gateway, and `.env` files are ignored by Git.

`application-dev.yml` contains all gateway configuration: port, routes, Redis, JWT, cookies, CORS, CSRF, and Actuator. The application entry point selects `dev` as the default profile when no profile is explicitly activated. Route destination URLs are configured directly in this file.

```bash
./mvnw spring-boot:run
```

Authentication in a `GlobalFilter` only covers routed requests. The CSRF controller explicitly applies the same cookie verification; exposed Actuator endpoints are denied externally. Spring Security supplies CORS/CSRF without duplicating routed JWT authentication. Do not add public local controllers without defining their security policy.

Backend ports must be private or restricted to trusted callers: downstream decoders deliberately do not verify JWT signatures. This change does not provision network restrictions, rate limiting, or WebSocket session revocation.

## Validation

```bash
./mvnw clean verify
```

HTTP integration tests use signed JWTs and a local stub backend. Redis errors/revocation are simulated there so the standard suite does not need Redis. To also run tests against an isolated real Redis instance, supply its port:

```bash
./mvnw clean verify -Dgateway.test.redis-port=6379
```

The optional integration tests use localhost, no password, database 4, and random keys which they delete afterward. Use a disposable test Redis, not the application's Redis. The same option in user-service enables the concurrent refresh test.

## SendGrid webhook routing

The existing `/notification/**` route preserves the context path, raw body and SendGrid signature headers. `POST /notification/api/webhooks/sendgrid` is public and excluded from CSRF when enabled. SendGrid requests do not contain user cookies and pass through the standard authentication filter. Notification verifies the original signed bytes before parsing or persisting. Other paths and methods remain protected. Configure a public HTTPS Gateway URL in SendGrid and keep backend ports private; see [Notification webhook setup](../notification-service/README.md#sendgrid-event-webhook).
