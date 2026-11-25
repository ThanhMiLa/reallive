# Virtual Gift Donation Implementation Plan

## 1. Goal & Scope

Implement the **Instant Virtual Gift Donation (Buy & Donate)** feature across `stream-service`, `api-gateway`, `payment-service`, and `web-app`:

- **Direct Buy-and-Donate (no gift inventory/backpack):** 1 gift per donation, all balances and prices in coins, 100% of `coin_price` credited directly to `streamer_wallets.available_balance` (no platform fee split in this phase).
- **No realtime WebSocket/Kafka broadcast** to other viewers in this phase.
- **`GET /payment/api/gifts` is PUBLIC** (permitted in both `api-gateway` and `payment-service` `PUBLIC_ENDPOINTS_GET`); **`POST /payment/api/donations` is AUTHENTICATED**.
- **Stream validation:** `payment-service` authenticates the donor via Redis auth state and calls `stream-service` (`GET /internal/api/streams/{sessionId}/donation-context`) outside the DB transaction to verify the stream is `PUBLIC` and `LIVE`, resolve `streamerId`/`channelId`/`ownerUserId`, and reject self-donations.

---

## 2. Ownership Boundaries

- **`codex-1` (Backend):** `stream-service/**`, `api-gateway/**`, `payment-service/**`. Do NOT edit `web-app/**`.
- **`antigravity-2` (Frontend):** `web-app/**`. Do NOT edit `stream-service/**`, `api-gateway/**`, `payment-service/**`, or `docs/**`.

---

## 3. Locked Shared API & Data Contracts

### 3.1 Internal Stream Donation Context (`stream-service`)
- **Endpoint:** `GET /stream/internal/api/streams/{sessionId}/donation-context` (internal only; never routed by `api-gateway`).
- **Response (`200 OK`, `code = 1000`):**
```json
{
  "status": 200,
  "code": 1000,
  "message": "Donation context resolved",
  "result": {
    "sessionId": "uuid",
    "channelId": "uuid",
    "streamerId": "uuid",
    "ownerUserId": "uuid",
    "visibility": "PUBLIC",
    "status": "LIVE"
  },
  "timestamp": "2026-11-25T07:00:00Z"
}
```
- **Not Found (`404`):** existing `ErrorCode.STREAM_SESSION_NOT_FOUND`.

### 3.2 List Active Virtual Gifts (`payment-service`)
- **Endpoint:** `GET /payment/api/gifts` (**PUBLIC** `GET` only; non-GET and child paths stay authenticated).
- **Ordering:** `coin_price ASC, name ASC`.
- **Response (`200 OK`, `code = 0`):**
```json
{
  "status": 200,
  "code": 0,
  "message": "Virtual gifts retrieved successfully",
  "result": [
    {
      "id": "00000000-0000-4000-8000-000000000101",
      "name": "Rose",
      "imageUrl": "/gifts/rose.svg",
      "coinPrice": 1
    }
  ],
  "timestamp": "2026-11-25T07:00:00Z"
}
```

### 3.3 Donate Virtual Gift (`payment-service`)
- **Endpoint:** `POST /payment/api/donations` (**AUTHENTICATED**).
- **Request Body:**
```json
{
  "streamId": "uuid",
  "giftId": "uuid",
  "idempotencyKey": "uuid"
}
```
- **Response (`200 OK`, `code = 0`):**
```json
{
  "status": 200,
  "code": 0,
  "message": "Gift donated successfully",
  "result": {
    "donationId": "uuid",
    "streamId": "uuid",
    "channelId": "uuid",
    "streamerId": "uuid",
    "giftId": "uuid",
    "giftName": "Rose",
    "giftImageUrl": "/gifts/rose.svg",
    "coinPrice": 1,
    "remainingBalance": 499,
    "createdAt": "2026-11-25T07:05:12.123"
  },
  "timestamp": "2026-11-25T07:05:12.123Z"
}
```

### 3.4 Payment Service Error Codes (`ErrorCode.java`)
- Existing reused: `1001` (`401` `UNAUTHENTICATED`), `1002` (`403` `ACCESS_DENIED`), `1003` (`503` `PAYMENT_DATA_UNAVAILABLE`), `1005` (`503` `AUTH_STATE_UNAVAILABLE`), `1006` (`404` `WALLET_NOT_FOUND`), `1009` (`400` `INVALID_REQUEST`).
- New (`1014`–`1022`):
  - `1014` (`404`) `STREAM_SESSION_NOT_FOUND`: `"Stream session not found"`
  - `1015` (`409`) `STREAM_NOT_LIVE`: `"Stream is not live"`
  - `1016` (`403`) `STREAM_NOT_ACCESSIBLE`: `"Stream is not accessible"`
  - `1017` (`503`) `STREAM_SERVICE_UNAVAILABLE`: `"Stream service unavailable"`
  - `1018` (`400`) `SELF_DONATION_NOT_ALLOWED`: `"You cannot donate to your own stream"`
  - `1019` (`404`) `VIRTUAL_GIFT_NOT_FOUND`: `"Virtual gift not found"`
  - `1020` (`409`) `VIRTUAL_GIFT_INACTIVE`: `"Virtual gift is no longer available"`
  - `1021` (`400`) `INSUFFICIENT_BALANCE`: `"Insufficient coin balance"`
  - `1022` (`409`) `IDEMPOTENCY_KEY_CONFLICT`: `"Idempotency key was already used for a different donation"`

### 3.5 Seeded Virtual Gifts (`V5__seed_default_virtual_gifts.sql` & `web-app/public/gifts/*.svg`)
| Fixed UUID | Name | `image_url` | `coin_price` | `status` |
| --- | --- | --- | --- | --- |
| `00000000-0000-4000-8000-000000000101` | `Rose` | `/gifts/rose.svg` | `1` | `ACTIVE` |
| `00000000-0000-4000-8000-000000000102` | `Heart` | `/gifts/heart.svg` | `5` | `ACTIVE` |
| `00000000-0000-4000-8000-000000000103` | `Fire` | `/gifts/fire.svg` | `10` | `ACTIVE` |
| `00000000-0000-4000-8000-000000000104` | `Star` | `/gifts/star.svg` | `25` | `ACTIVE` |
| `00000000-0000-4000-8000-000000000105` | `Crown` | `/gifts/crown.svg` | `100` | `ACTIVE` |
| `00000000-0000-4000-8000-000000000106` | `Diamond` | `/gifts/diamond.svg` | `250` | `ACTIVE` |
| `00000000-0000-4000-8000-000000000107` | `Rocket` | `/gifts/rocket.svg` | `500` | `ACTIVE` |
| `00000000-0000-4000-8000-000000000108` | `Galaxy` | `/gifts/galaxy.svg` | `1000` | `ACTIVE` |

---

## 4. Backend Plan (`codex-1`)

Follow `AGENTS.md` and every applicable Java rule under `.agents/rules/` (`java-code-style` + `lombok`, `records`, `repository-queries`, `service-javadoc`, `java-architecture`, `java-exception-handling`, `feign-client`, `security-endpoint-constants`, `internal-service-access`) and `docs/data-model/Data Model.md`.

### 4.1 `stream-service`
- Add `"/internal/api/streams/*/donation-context"` to `INTERNAL_ENDPOINTS_GET` in `common/security/SecurityConfig.java`.
- Add `stream/dto/response/DonationContextResponse.java`, `stream/service/DonationContextService.java` (with English Javadoc), `stream/service/impl/DonationContextServiceImpl.java` (`@Transactional(readOnly = true)`), and `stream/controller/internal/InternalDonationContextController.java` (`GET /internal/api/streams/{sessionId}/donation-context`, `code = 1000`).
- Add `@WebMvcTest` `InternalDonationContextControllerTest.java` verifying unauthenticated internal GET succeeds without JWT/Redis authorities, 404 when missing, 400 on invalid UUID.

### 4.2 `api-gateway`
- Add `"/payment/api/gifts"` to `PUBLIC_ENDPOINTS_GET` in `filter/AuthenticationGlobalFilter.java`.
- Update `GatewayAuthenticationTest.java`: add `"payment"` route to dynamic test routes and assert `GET /payment/api/gifts` without cookies reaches backend without Redis lookup, rejects invalid access cookie (`401`), and keeps `POST /payment/api/gifts`, `GET /payment/api/gifts/extra`, and `POST /payment/api/donations` authenticated.
- Update `StreamInternalRouteTest.java`: add `"GET, /stream/internal/api/streams/id/donation-context"` to `internalEndpointsAreNeverRoutedWithOrWithoutAccountCredentials`.

### 4.3 `payment-service`
- **Security:** Add `"/api/gifts"` to `PUBLIC_ENDPOINTS_GET` in `common/security/SecurityConfig.java`.
- **Config:** Add `app.service.stream` (`${STREAM_SERVICE_BASE_URL:http://localhost:8082/stream}` in `application-dev.yaml`, `http://localhost:18082/stream` in `application-test.yaml`, `STREAM_SERVICE_BASE_URL=http://localhost:8082/stream` in `.env.example`) plus OpenFeign timeouts (`connectTimeout: 2000`, `readTimeout: 5000`) for `stream-service` and `streamDonationContext`.
- **Migrations (PostgreSQL + H2 `MODE=PostgreSQL` compatible):**
  - `V4__create_virtual_gifts_and_donations_tables.sql`: create `virtual_gifts` (`pk_virtual_gifts`, `uk_virtual_gifts_name`, `ck_virtual_gifts_coin_price`, `ck_virtual_gifts_status`, `idx_virtual_gifts_status`) and `donations` (`pk_donations`, `fk_donations_gift`, `uk_donations_donor_idempotency UNIQUE (donor_user_id, idempotency_key)`, `ck_donations_coin_price`, `ck_donations_status`, indexes `idx_donations_donor_created_at`, `idx_donations_streamer_created_at`, `idx_donations_stream_created_at`, `idx_donations_channel_id`, `idx_donations_gift_id`) synchronized with `docs/data-model/Data Model.md`.
  - `V5__seed_default_virtual_gifts.sql`: insert the 8 active gifts from Section 3.5.
- **ErrorCode:** Add constants `1014`–`1022` from Section 3.4.
- **Wallet Repositories:**
  - `UserWalletRepository.debitBalanceByUserId(UUID userId, long coinAmount, LocalDateTime updatedAt)` with `@Modifying(clearAutomatically = true, flushAutomatically = true)` and `WHERE w.userId = :userId AND w.balance >= :coinAmount`.
  - `StreamerWalletRepository.creditAvailableBalanceByStreamerId(UUID streamerId, long amount, LocalDateTime updatedAt)` with `@Modifying(clearAutomatically = true, flushAutomatically = true)`.
- **Module `com.reallive.paymentservice.donation`:**
  - `entity`: `VirtualGiftEntity` (extends `BaseEntity`, `Status` enum `ACTIVE, INACTIVE`), `DonationEntity` (`Status` enum `COMPLETED, REFUNDED`).
  - `repository`: `VirtualGiftRepository` (`findAllByStatusOrderByCoinPriceAscNameAsc`), `DonationRepository` (`findByDonorUserIdAndIdempotencyKey`).
  - `client.stream`: `StreamDonationContextResponse`, `StreamDonationContextClient` (`@FeignClient(name = "stream-service", contextId = "streamDonationContext", url = "${app.service.stream}")`), `StreamDonationContextApiClient` (maps `404 -> STREAM_SESSION_NOT_FOUND`, other failures/malformed envelopes/missing IDs -> `STREAM_SERVICE_UNAVAILABLE`, expects `status == 200` and `code == 1000`).
  - `dto.request`: `DonateGiftRequest(@NotNull UUID streamId, @NotNull UUID giftId, @NotNull UUID idempotencyKey)`.
  - `dto.response`: `VirtualGiftResponse`, `DonateGiftResponse`.
  - `service` & `service.impl`: `VirtualGiftService`/`VirtualGiftServiceImpl`, `DonationService`/`DonationServiceImpl` (English Javadoc on interfaces; reuse existing `Clock paymentClock` bean and `TransactionTemplate`):
    1. Outside TX: fast-path `findByDonorUserIdAndIdempotencyKey` -> if found, verify `streamId` and `giftId` match (else `IDEMPOTENCY_KEY_CONFLICT`) and return `DonateGiftResponse` with current wallet balance without calling `stream-service`.
    2. Outside TX: call `StreamDonationContextApiClient.getDonationContext(streamId)` -> require `visibility == "PUBLIC"` (`STREAM_NOT_ACCESSIBLE`), `status == "LIVE"` (`STREAM_NOT_LIVE`), `!donorUserId.equals(ownerUserId)` (`SELF_DONATION_NOT_ALLOWED`).
    3. Inside single `TransactionTemplate`:
       - Load `VirtualGiftEntity` (`VIRTUAL_GIFT_NOT_FOUND`), require `ACTIVE` (`VIRTUAL_GIFT_INACTIVE`).
       - `saveAndFlush` `DonationEntity` (`COMPLETED`, `coinPrice` snapshot) BEFORE debiting `user_wallets` so concurrent duplicate `idempotencyKey` requests hit `uk_donations_donor_idempotency` cleanly instead of falsely returning `INSUFFICIENT_BALANCE`.
       - Atomic `debitBalanceByUserId(donorUserId, coinPrice, now)` -> if `0`, distinguish `WALLET_NOT_FOUND` vs `INSUFFICIENT_BALANCE` (rolls back `donations`).
       - Reload `UserWalletEntity` via `findByUserId(donorUserId)` for `walletId` and post-debit `remainingBalance`.
       - Save `WalletTransactionEntity` (`walletId`, `type = GIFT_PURCHASE`, `amount = coinPrice`, `referenceId = donation.getId()`, `createdAt = now`).
       - `streamerWalletRepository.insertIfAbsent(streamerId)` + `creditAvailableBalanceByStreamerId(streamerId, coinPrice, now)` (require `== 1`, else `PAYMENT_DATA_UNAVAILABLE`).
    4. Outside TX catch `DataIntegrityViolationException`: re-query `findByDonorUserIdAndIdempotencyKey` to return idempotent response (or throw `IDEMPOTENCY_KEY_CONFLICT` if payload differs, or `PAYMENT_DATA_UNAVAILABLE` if unrelated).
  - `controller.external`: `VirtualGiftController` (`GET /api/gifts`, `code = 0`) and `DonationController` (`POST /api/donations`, `code = 0`).
- **Tests:** `StreamDonationContextApiClientTest`, `DonationServiceImplTest`, and `DonationIntegrationTest` (modeled after `StreamerWalletIntegrationTest` with embedded `HttpServer` stub for `stream-service`, verifying public `GET /payment/api/gifts`, authenticated `POST /payment/api/donations`, no active DB transaction during remote HTTP call, all error branches + rollback, sequential & concurrent idempotency replay/conflict, and concurrent tight-balance debit).

### 4.4 Backend Verification Commands
```bash
cd stream-service && ./mvnw clean verify
cd api-gateway && ./mvnw clean verify
cd payment-service && ./mvnw clean verify
```

---

## 5. Frontend Plan (`antigravity-2`)

Follow `AGENTS.md` and `web-app/AGENTS.md`:
- Create 8 custom SVG icons under `web-app/public/gifts/*.svg` matching Section 3.5.
- Add types (`VirtualGift`, `DonateGiftRequest`, `DonateGiftResponse`) in `web-app/src/types/payment.types.ts` and endpoints (`getVirtualGifts`, `donateGift`) in `web-app/src/api/payment.api.ts`.
- Create `useVirtualGifts.ts` and `useDonateGift.ts` (1-click send, `idempotencyKey` retry preservation on network/5xx, clearing on 200/4xx, instant `['wallet', userId]` cache update, `1015` metadata refresh, `1020` gift catalog refetch).
- Build TikTok Live-style `StreamGiftBar.tsx` and integrate directly below the video player in `WatchStreamPage.tsx`.
- Update `watch-stream.test.tsx`, extend `payment-deposit.test.mjs`, create `tests/streaming/stream-gift-bar.test.tsx`, and verify `npm run build && npm run lint && npm test && npm run test:streaming`.
