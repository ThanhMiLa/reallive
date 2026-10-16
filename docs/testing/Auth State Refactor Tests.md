# Auth state refactor verification

Verified on 2026-10-15 against the [implementation plan](../Implement%20Plan/Authentication%20and%20Authorization%20Refactor.md) and [Auth State contract](../contracts/Auth%20State.md).

## Module verification

All five affected Java modules passed `./mvnw clean verify`. Real-Redis tests were enabled using disposable Redis on loopback port 16389. Counts below come from the modules' Surefire XML reports, with zero failures, errors or skipped cases.

| Module | Tests passed |
|---|---:|
| User Service | 706 |
| API Gateway | 81 |
| Channel Service | 348 |
| Payment Service | 92 |
| Notification Service | 269 |
| Total | 1496 |

Run Maven inside the corresponding module. To enable the additional Redis cases:

```sh
./mvnw clean verify -Dauth.test.redis-port=16389 -Dgateway.test.redis-port=16389 -Duser.test.redis-port=16389
```

These options enable auth-state, gateway/refresh and existing OTP integration tests. Use an isolated, disposable Redis without credentials; these suites use logical databases 4, 14 and 15. Default tests use mocks when Redis is not the subject of the test.

## Requirements and evidence

| Requirement | Verification |
|---|---|
| Access/refresh JWTs contain no scope | `AuthTokenServiceImplTest`, real-Redis HTTP contract test and packaged-app HTTP check |
| Every issuance path publishes current account state | Auth service/controller tests for login, registration, refresh and password change |
| Authorities come from Redis, including for legacy scoped JWTs | `SecurityConfigTest` and `RedisAuthoritiesReaderTest` in all four downstream services |
| New permissions work with the existing access token | `AuthStateContractIntegrationTest` and packaged-app HTTP check |
| Permission removal immediately denies access | Role-permission integration case and packaged-app HTTP check |
| Account ban is enforced at gateway | `GatewayAuthenticationTest` and packaged-app HTTP check |
| Public endpoints skip account-status/authorities lookups | Gateway public-endpoint and downstream public/internal security tests |
| Redis/missing/corrupt state returns 503 | Reader tests, servlet security tests, gateway status failure/timeout tests and packaged-app timeout case |
| TTL extends on refresh and never shortens on synchronization | Real-Redis store and HTTP contract integration tests |
| Stale snapshots cannot overwrite newer state | Real-Redis revision/concurrent publication tests |
| Refresh replay is rejected atomically | Real-Redis concurrent refresh and auth-controller integration cases |
| Database rollback does not grant a role in Redis | Streamer registration and auth-state transaction integration cases |
| Committed publication failure retains a durable task | Auth-state HTTP integration failure case, attempt counter and fresh retry-worker execution |
| Role-wide permission updates are synchronized | Role-permission integration case using `rolePermissionsChanged` |
| Existing users can be backfilled without token issuance | Auth-state integration case executing the batch runner |
| Business services no longer check account active | Production-source audit plus updated service/controller responsibility tests |
| Other sessions survive logout | Auth store/session tests retaining account-wide state |
| Historical database migrations still upgrade | Flyway migration tests and packaged apps starting on fresh PostgreSQL 17 |
| Frontend distinguishes 503 from an expired session | Web build/lint and a runtime check of the actual Zustand store |

## Packaged-app HTTP verification

The repeatable harness is [auth_state_e2e.py](../../scripts/testing/auth_state_e2e.py). Build API Gateway, User Service and Channel Service jars first, then run from the repository root:

```sh
python3 scripts/testing/auth_state_e2e.py
```

Prerequisites: Java 21, Python 3 and Docker with PostgreSQL 17 Alpine and Redis 7.4 Alpine available. The harness starts its own disposable PostgreSQL/Redis containers, starts the packaged applications on separate loopback ports, creates synthetic fixture data and stops its applications/containers in `finally`. It preserves application logs in the temporary directory printed by the script.

All checks passed:

1. Login issues an unscoped token; channel update initially returns 403.
2. Become-streamer completes through Gateway → User → Channel, then channel update returns 200 with the unchanged access token.
3. Committed ban/unban tasks are consumed by the running worker; gateway returns 403/200 with that same token. Login while banned returns 403.
4. Permission removal/restoration returns 403/200 without replacing the token.
5. Pausing Redis causes a bounded 503 response without clearing cookies. Unpausing Redis restores access, and refresh publishes a fresh token pair and auth state.

Ban/unban and permission-mutation APIs do not yet exist in this repository. The harness records their equivalent database mutation and durable task in a single transaction; the service-level integration suite separately tests the synchronization hooks those future APIs must call.

## Web and source checks

- `npm run build`: passed.
- `npm run lint`: passed.
- Runtime check of the actual TypeScript auth store using TypeScript transpilation, Zustand and Axios: 503 retains the existing session; 401 clears it; successful retry restores the user.
- Production-source audit: no `validateActive`, `validateActiveUser`, `UserEntity.isActive`, `buildScope`, JWT scope issuance or legacy scope-authorities converter remains. Account eligibility at token issuance is centralized in `TokenIssuancePolicy`.
- Backend development defaults bind to loopback. Deployment overrides must retain the trusted-gateway network boundary.

## Operational behavior

Migration V8 adds account auth revisions and durable synchronization tasks. Existing account caches must be backfilled before switching all readers to Redis; the opt-in setting is `AUTH_STATE_BACKFILL_ENABLED=true` for a controlled startup. After backfill, restore it to false.

A publication failure after database commit returns 503 while the committed synchronization task remains available to retry. A Redis response timeout after a refresh script executed can have an uncertain outcome; strict refresh replay protection remains in force. See the contract for recovery and rollback requirements.
