# Authentication and authorization state

## Ownership and request flow

User Service owns account status, roles, permissions and all writes to auth-state keys. API Gateway verifies signed JWTs and token revocation before reading the authenticated account's status on protected endpoints. Downstream services authenticate any supplied access-token cookie and load current authorities from Redis, including on public endpoints. Spring method authorization applies the endpoint's access rules. Business services do not check `users.status`.

JWTs contain `sub`, `iss`, `iat`, `exp`, `jti` and `type` (`ACCESS` or `REFRESH`). They carry no role or permission claims. Existing signed tokens containing `scope` remain usable, but their scope is ignored.

| Redis key | String value | Readers |
|---|---|---|
| `auth:authorities:{userId}` | JSON string array, e.g. `["CHANNEL_UPDATE","ROLE_STREAMER","ROLE_USER"]` | User, Channel, Payment and Notification security |
| `auth:status:{userId}` | Exactly `ACTIVE` or `BANNED` | API Gateway |
| `auth:revision:{userId}` | Committed account auth revision as a decimal integer | User Service publication script |
| `auth:revoked:access:{jti}` | `1` | Gateway and User Service |
| `auth:revoked:refresh:{jti}` | `1` | Gateway and User Service |

Authorities are deduplicated and sorted. Role names use `ROLE_`; permission names retain their database values. An empty array is valid and grants no application authorities. A missing key, malformed array, blank/non-string authority or unknown status is an unavailable auth state, never an implicit active account or default role.

The three account keys share a minimum lifetime of `AUTH_REFRESH_TOKEN_TTL + AUTH_STATE_TTL_GRACE`. Every token-pair issuance republishes both status and authorities and extends the lifetime. Publication preserves any longer existing TTL. Logout revokes only the supplied session tokens and retains account-wide state for other devices.

## Failure contract

| Condition | HTTP | Behavior |
|---|---|---|
| Missing required JWT, invalid/expired JWT, revoked/replayed token | 401 | Reject authentication |
| Banned account | 403 | Gateway rejects protected requests; issuance policy rejects new access/refresh credentials |
| Insufficient authorities | 403 | Downstream method authorization rejects the operation |
| Redis error, timeout, missing or corrupt auth state | 503 | Reject without executing the business operation; retain valid cookies |

Public endpoints allow requests without an access-token cookie and do not require an account-status lookup at Gateway. Supplied token cookies are still validated by Gateway according to its cookie contract. Downstream cookie resolvers do not skip public or internal paths: when an access token is supplied, it is decoded and current authorities are loaded from Redis so the JWT principal is available to the endpoint. Without an access token, no authorities lookup is performed. Invalid supplied tokens are rejected; unavailable authorities return 503 even on public endpoints. Token issuance uses a centralized eligibility policy; public password recovery performs its own OTP/reset-token checks without an account-active business validator.

`GET /channel/api/channels` supports optional authentication through the same filter chain as other Channel Service endpoints. Without an access-token cookie it lists active, non-deleted channels without ownership/follow exclusions or user-interest lookup. With a valid access token it retains the user's ownership/follow exclusions and interest-based ordering when no explicit filters are supplied. `GET /channel/api/channels/following` remains protected.

Normal requests never fetch auth state from User Service on a Redis miss. Login or refresh republishes it. Dependent services must use the same Redis host, logical database and key serialization. Development backends bind to `127.0.0.1` by default through `BACKEND_BIND_ADDRESS`. If overridden for deployment, backend ports must remain restricted to trusted callers because downstream JWT decoders trust gateway validation.

Channel ownership, channel-specific moderators, bans on chat/viewing, email verification and streamer status remain separate business rules. Future Stream, VOD and Chat authentication must follow this contract; mobile clients continue using the gateway cookie API.

## Committed publication and recovery

`AuthTokenService` prepares the signed pair and a detached authorities snapshot inside the database transaction. It publishes only after commit and before the controller emits cookies. Refresh consumption and publication are a single Redis script, so a cache-write failure does not separately consume the old refresh token. Concurrent replay permits at most one successful consumption. A connection timeout after Redis executed the script has an uncertain outcome: both publication and revocation may already have succeeded even though the client receives 503. Cookie retention does not make that consumed refresh token reusable; recovery may require login.

Role/status mutations must lock the account and call `AuthStateSyncService.changed(user)` in their transaction. Permission changes affecting a role call `rolePermissionsChanged(roleId)` in the mutation transaction. The hook advances `users.auth_revision` and records `auth_state_sync_tasks` atomically with the database change. After commit, current state is read in a separate transaction and synchronously published. Older revisions cannot overwrite newer Redis state. The scheduled worker retries committed pending tasks without repeating the original business operation.

An HTTP 503 after database commit can therefore mean the database mutation succeeded but publication remains pending. The task survives restart. A successful response means synchronous publication completed. Register/change-password may also commit before publication fails; no cookies are emitted on that failure, and login can recover auth state.

## Deployment and backfill

1. Apply the User Service auth-revision/task migration before switching readers.
2. In a staged release, deploy auth-state writers while legacy readers still receive scoped tokens.
3. Set `AUTH_STATE_BACKFILL_ENABLED=true` for a controlled User Service startup to publish existing users in batches. It issues no JWTs and preserves blacklist keys. Stop rollout if backfill fails; rerunning is safe. Return this setting to false afterward.
4. Deploy Redis authorities readers and gateway status enforcement together with the removal of business account-active checks. Ensure backend network restrictions are in place.
5. Switch issuance to tokens without scope after all readers use Redis. The final implementation in this repository is this target state.
6. Roll back only to a release that accepts tokens without scope and still reads Redis once unscoped tokens have been issued. Retain account keys, revisions and pending tasks.

| Environment variable | Default | Purpose |
|---|---|---|
| `AUTH_STATE_TTL_GRACE` | `60s` | Margin beyond refresh-token lifetime |
| `AUTH_STATE_RETRY_ENABLED` | `true` | Enable durable synchronization retries |
| `AUTH_STATE_RETRY_DELAY` | `5s` | Retry interval and delay after a failed attempt |
| `AUTH_STATE_BACKFILL_ENABLED` | `false` | Opt-in startup backfill of existing accounts |
| `REDIS_CONNECT_TIMEOUT` / `REDIS_TIMEOUT` | `2s` | Bound connection and command waits |

Credentials belong in environment variables. Tests against real Redis use isolated databases and random user IDs; never point destructive test cleanup at an application database.

## Verification

Run `./mvnw clean verify` in each of API Gateway, User, Channel, Payment and Notification services. Default tests use mocked auth Redis where network integration is not under test. An additional real-Redis contract suite is available in User Service:

```sh
./mvnw clean verify -Dauth.test.redis-port=16389
```

This opt-in suite uses a disposable Redis on `127.0.0.1`, logical database 14, without credentials. It verifies immediate permission changes with an unchanged JWT, post-commit retry, rollback, ban/unban synchronization, refresh replay, TTL renewal and atomic Redis publication. It deletes only the random keys it creates. Do not use a production Redis instance.
