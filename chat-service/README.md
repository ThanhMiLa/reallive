# Chat Service

NestJS + Socket.IO + MongoDB/Mongoose livestream chat. See [the contract](../docs/contracts/chat/README.md) and [verification notes](../docs/testing/Chat%20MVP.md).

## Source organization

Chat stays in one feature module. Files inside `src/chat/` are grouped by responsibility:

```text
chat/
  chat.module.ts
  chat.spec.ts
  controllers/chat.controller.ts
  gateways/chat.gateway.ts
  services/
    chat.service.ts
    chat-access.service.ts
    chat-auth.service.ts
    chat-history.service.ts
    chat-database.service.ts
  clients/chat-clients.service.ts
  repositories/chat.repository.ts
  mappers/chat.mapper.ts
  schemas/
    chat-message.schema.ts
    chat-counter.schema.ts
  dto/
    chat-message.dto.ts
    chat-history.dto.ts
  interfaces/
    chat.types.ts
    chat-history.interface.ts
    create-message.interface.ts
  utils/
    chat-cursor.ts
    room-queue.ts
```

Controllers handle HTTP; gateways handle Socket.IO; clients call other application services. `ChatService` owns authorization, send orchestration and idempotency decisions. `ChatHistoryService` validates cursors and builds pagination boundaries. `ChatDatabaseService` validates database readiness. `ChatRepository` only performs MongoDB queries, writes and transaction execution; it does not depend on response DTOs, mappers or application error codes. `ChatMapper` converts service input to documents and stored documents/page metadata to public responses.

The send service executes request-ID lookup, counter increment and message insertion in one repository transaction. It maps the committed result and broadcasts only newly created messages after commit. MongoDB can retry the transaction callback without allocating a different message ID or broadcasting a duplicate.

Schemas define MongoDB document shapes and their TypeScript interfaces, serving the role of persistence entities. DTO interfaces describe history query and public message/page shapes; runtime validation remains in the service layer. Other internal and integration contracts live in `interfaces/`.

## Local startup

1. Run `npm ci` in this directory.
2. Copy `.env.example` to `.env`. Supply the existing `JWT_SIGNER_KEY` and Redis connection shared by User Service/Gateway. The private Stream/Channel/User chat endpoints use `permitAll()` and are called without internal-token or user credentials. Never commit `.env`.
3. Set `MONGODB_URI` to an available MongoDB replica set, including its hosts, credentials when required, database and replica-set name. Chat uses transactions and requires a replica set.
4. Run `npm run db:indexes`. Startup/readiness reject a standalone database or missing required unique indexes.
5. Apply Channel Service migration V5 through its normal startup/Flyway process. Deploy the private integration endpoints in User/Channel/Stream Service before starting chat.
6. Run `npm run start:dev`. It listens on `127.0.0.1:3001` by default.
7. Start/restart Gateway with the chat HTTP/WS routes and run Web. Web continues to use `VITE_API_BASE_URL=http://localhost:8888`.

The default service URLs include the Java servlet context paths. Docker deployments must change these URLs and Gateway `CHAT_HTTP_URI`/`CHAT_WS_URI` to reachable service addresses. Bind internal ports only on a trusted network. Keep Gateway and Socket.IO origins explicit.

## Verification

```sh
npm run build
npm run lint
npm test -- --runInBand
npm run test:integration
npm run test:baseline
```

`test:integration` creates uniquely named disposable MongoDB replica-set/Redis containers on available localhost ports, runs HTTP and real Socket.IO tests, then stops its own containers. Docker must be available. The test database name starts with `chat_it_`; teardown refuses to drop a differently named database. The tests never point at application MongoDB or Redis.

`test:baseline` runs the explicit performance scenario and updates `docs/testing/Chat Baseline.json`. It is a local measurement with mock Java integrations, not a production capacity claim.

For already provisioned isolated test containers, `npm run test:e2e -- --runInBand` uses ports 27028/16388 by default; override `CHAT_TEST_MONGO_PORT` and `CHAT_TEST_REDIS_PORT` to point only at disposable test infrastructure.

## Operations and current limits

- Live: `GET /internal/health/live`; ready: `GET /internal/health/ready`. Gateway does not expose them. Readiness checks Redis, MongoDB replica-set configuration and required indexes.
- Run one Chat Service instance. No Redis Socket.IO adapter or multi-instance room sequencing is supported in this MVP.
- Message persistence is authoritative. Restart causes socket reconnect/catch-up, and counters/idempotency survive restart.
- Missing auth state, expired credentials and unavailable dependencies reject access. Do not recover Redis misses by granting default roles or querying account status from User Service.
- Dependency failures reject the current operation and may be retried; a 503 is not automatically classified as a stream ending.
- Session/channel access is cached for at most 1,750 ms and refreshed on demand. No periodic participant checks or automatic idle-room closure is implemented; active actions validate access. Passive ban/revocation/stream-end enforcement is deferred.
- No automatic message TTL, avatar deletion, spam policy, moderation commands, VOD chat replay or Android client is implemented.
- Retaining old avatar objects is intentional and grows object storage until retention/cleanup is defined.
- Do not log cookies, JWTs, internal tokens or message/profile bodies. Current error logs contain only an operation and exception type.
- HTTP error rate, disconnect reasons, MongoDB transaction latency, resource use and deep-page explain results should be monitored before raising load or enabling multiple instances.
