# Notification Service

Java 21 / Spring Boot service responsible for outbound email through SendGrid and delivery history. User Service owns OTP generation, verification, cooldown and streamer registration.

## Email package structure

| Package | Responsibility |
|---|---|
| `email.consumer` | Receive and deserialize Kafka email requests; delegate delivery |
| `email.controller` | Expose email HTTP endpoints, including the SendGrid webhook receiving raw body and signature headers |
| `email.service` / `email.service.impl` | Coordinate email rendering, delivery attempts, deduplication and history; handle webhook signature verification and delivery events through a dedicated service |
| `email.client.sendgrid` | Call SendGrid and interpret provider submission responses |
| `email.repository` / `email.entity` | Store notification requests and delivery history in the Notification database |
| `email.dto.request` / `email.dto.response` | Define transport contracts when Kafka and webhook integration are implemented |

The Kafka listener, delivery/history services, SendGrid client and signed Event Webhook are implemented. The webhook uses `SendGridWebhookController` and `SendGridWebhookService` with `SendGridWebhookServiceImpl`; no additional table or SDK is required.

`email.configuration` provides validated email/provider settings, request/dead-letter topics, a UTC Clock, a JDK-backed WebClient and bounded Kafka error handling. The listener uses record acknowledgements, retries twice by default, and acknowledges failures only after history finalization and durable dead-letter publication.

Notification does not check OTPs, query the User database, update `emailVerified`, or grant roles. Email delivery results remain in this service; User Service does not wait for webhook events. The Kafka wire DTO is defined in [the Kafka contract](../docs/contracts/Email%20Verification%20Kafka.md). SendGrid submission and signed webhook authentication are implemented.

## SendGrid DTOs

- `email.client.sendgrid.dto.request.SendGridMailRequest`: Dynamic Template payload with sender, `template_id`, recipients, OTP template data and `custom_args`. Its nested classes model email addresses, personalizations and OTP data; OTP is excluded from `toString()`.
- `email.client.sendgrid.dto.response.SendGridErrorResponse`: provider `errors` array, retaining the message and optional field while ignoring unused metadata.
- `email.dto.request.SendGridWebhookEventRequest`: one event in the incoming webhook array, with root-level `eventId` correlation, provider IDs, Unix timestamp in seconds and optional delivery details. Unknown provider fields are ignored.

Template selection belongs to Notification configuration. Mail Send success is HTTP 202 with an empty body; the client reads `X-Message-ID` when present. `SendGridSubmissionResult` is an internal outcome, not a provider JSON success-body DTO. The webhook handler verifies signatures against original raw bytes before deserializing events.

DTO tests cover SendGrid field names, OTP leading zeros and exclusion from `toString()`, optional sender names, error metadata, and webhook arrays with missing/extra fields. They do not call SendGrid.

## Kafka delivery workflow

1. `EmailVerificationConsumer` validates the version, required fields, six ASCII OTP digits, recipient, timestamps and Kafka key before calling the delivery service. Invalid JSON is translated to a fixed `AppException` without retaining its raw cause.
2. History is inserted and committed before sending. `event_id` is unique. A short transaction locks the event, verifies immutable metadata, expiry, retry schedule and attempt budget, then commits `PROCESSING` and increments the attempt count. No database transaction surrounds the HTTP call.
3. `EmailVerificationDeliveryServiceImpl` calls `email.util.SendGridMailRequestBuilder` to build `SendGridMailRequest` using the template stored on the first request, configured sender, recipient, OTP and `custom_args.eventId`. The template must contain an active version using `{{otp}}` and `{{expiresInMinutes}}`; describe validity from the OTP request time. Resending or receiving email does not extend the User Service Redis deadline.
4. `SendGridClient` only calls Mail Send with the prepared DTO; the delivery service handles expiry, timeout, response classification and persistence. HTTP 202 is persisted as `ACCEPTED`, including the provider message ID when supplied. Missing ID or empty success body is supported. An accepted duplicate remains accepted even after its OTP deadline.
5. Completed 429/5xx responses and proven pre-send connection failures may retry. `next_attempt_at` respects configured backoff and `Retry-After`. Both Kafka retry count and persisted attempt count bound retries; an attempt claimed before a crash also counts toward the budget. A retry beyond the OTP deadline becomes `EXPIRED`.
6. Permanent rejections become `FAILED`. Read timeout, uncertain transport errors or redelivery of `PROCESSING` become `UNKNOWN` and are not resent. A late known response may complete that same attempt, but UNKNOWN cannot reopen a retry schedule. This conservatively avoids concurrent provider calls; it does not promise exactly-once external email delivery.
7. `FAILED`/`UNKNOWN` redelivery still goes through DLT recovery so a previous DLT publication failure cannot be mistaken for success. Accepted/expired requests can finish without another DLT. Failure to finalize history or publish the DLT prevents recovery acknowledgement.

The JDK HTTP client does not follow redirects. Connect timeout defaults to 2 seconds and the total call budget to 10 seconds, shortened to the OTP time remaining. Settings are under `notification.email.sendgrid` in `application-dev.yaml`.

Logs and history exclude OTPs, credentials and raw request/response bodies. DLT payloads retain the original Kafka message including its transient OTP; restrict broker access and keep the configured short retention. Automatic tests never send email to the real provider.

## SendGrid Event Webhook

`POST /notification/api/webhooks/sendgrid` receives a JSON array through Gateway (port `8888` locally). It requires `X-Twilio-Email-Event-Webhook-Signature` and `X-Twilio-Email-Event-Webhook-Timestamp`, not a user cookie or CSRF token. Only this exact POST endpoint bypasses user authentication; Notification verifies ECDSA/SHA-256 over the signed timestamp followed by the original request bytes, using `SENDGRID_WEBHOOK_PUBLIC_KEY`. No body or signature is logged.

The service maps `processed`, `delivered`, `deferred`, `bounce` and `dropped` to `EmailDeliveryEventType` and stores uppercase enum names in `email_delivery_events` (migration V4 normalizes existing history), correlating root-level `eventId` with the existing notification. Correlated events require a nonblank `sg_event_id` and a valid Unix timestamp. A row lock plus the existing unique provider-ID constraint prevents concurrent redelivery from duplicating history. A batch commits atomically; malformed events return `400`, missing/invalid signatures return `401`, and database or verification-key configuration failures return `503`. HTTP `200` acknowledges committed events or duplicates. Submission status remains separate; webhook arrival never updates User Service or resends an email.

Events without RealLive correlation, unknown notification IDs and unsupported event types are acknowledged and ignored, including SendGrid's sample integration events. Out-of-order events remain separate rows, read chronologically by the existing repository. SMTP response/reason fields have control characters replaced with spaces and are capped at 2048 characters. The signed header timestamp is verified without a short freshness window so delayed provider retries remain valid; replayed event IDs are deduplicated.

For a real provider callback:

1. In the same SendGrid account used for Mail Send, open **Settings → Mail Settings → Event Webhooks**.
2. Set the POST URL to `https://<public-gateway-host>/notification/api/webhooks/sendgrid` and enable the five delivery event types above. A local test needs an HTTPS tunnel to Gateway port `8888`.
3. Enable **Signed Event Webhook**, save it, and copy its public verification key into Notification's `SENDGRID_WEBHOOK_PUBLIC_KEY`.
4. Restart Notification with the updated environment and use **Test Integration**. Test events without `eventId` return `200` without creating history; a real email sent through this service supplies the correlation ID.

Keep the Gateway public and backend ports private. User and Notification decoders retain the existing trusted-Gateway model. See [SendGrid webhook security](https://www.twilio.com/docs/sendgrid/for-developers/tracking-events/getting-started-event-webhook-security-features) for the signed-byte contract and setup.

## Email history persistence

Flyway `V1__create_email_history_tables.sql` creates two tables shared by all email types:

- `email_notifications`: Kafka `event_id`, user, recipient, email type, selected template, submission status, attempt count, latest HTTP/error details, provider message ID and timestamps. `event_id` is unique; `user_id` has no cross-service foreign key. Expiration is optional for emails without a deadline. Submission statuses are `PENDING`, `PROCESSING`, `ACCEPTED`, `FAILED`, `EXPIRED`, `UNKNOWN`.
- `email_delivery_events`: individual webhook events, linked by a required foreign key to a notification. Provider event IDs are unique when present; multiple null IDs are allowed. Events retain provider and receipt times, message ID and optional SMTP response/reason/status.

`EmailNotificationEntity` and `EmailDeliveryEventEntity` extend the service-local `common.entity.BaseEntity`, inheriting `createdAt` and `updatedAt` as `Instant` timestamps mapped to PostgreSQL `TIMESTAMPTZ`. Migration V2 adds audit columns to delivery events and backfills existing rows from `received_at`; the original receipt and provider timestamps remain separate. Delivery events use a lazy many-to-one relationship. `EmailType` reuses the current message enum; add types as new email flows are implemented. No OTP, OTP hash, challenge ID, template payload or metadata column is persisted. Migration V3 adds nullable `next_attempt_at` to preserve retry schedules across restart. Submission history stores fixed diagnostic codes such as `SENDGRID_HTTP_400`, not raw provider error messages. PostgreSQL timestamp precision is normalized to microseconds for immutable-message comparisons.

`EmailNotificationRepository` looks up a request by `eventId` and paginates a user's history with newest requests first. `EmailDeliveryEventRepository` looks up `sgEventId` and paginates a notification's delivery events by provider time and then receipt time. Resolve a webhook's `eventId` to the notification before storing the relationship. Provider submission status remains separate from delivery history.

## Configuration and local run

Development settings are in `application-dev.yaml`; activate the `dev` profile when running locally. Notification listens on port `8085` with context path `/notification`. It connects to its own PostgreSQL on host port `5437` by default, uses Flyway to create history tables and validates JPA mappings. Redis is not currently used, so its health contributor is disabled.

Keep `NOTIFICATION_DB_PASSWORD`, Kafka connection settings and the required `SENDGRID_API_KEY`, `SENDGRID_FROM_EMAIL`, `SENDGRID_EMAIL_VERIFICATION_TEMPLATE_ID`, and `SENDGRID_WEBHOOK_PUBLIC_KEY` in `notification-service/.env`, then export them or configure the IDE environment before starting. The root `.env.example` is for Docker Compose infrastructure only. Invalid or missing email/provider configuration fails startup. Do not commit real credentials. Sender name defaults to `RealLive`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Start Notification before User's email publisher: Notification provisions both topics through KafkaAdmin, which fails startup if Kafka is unavailable. Topic names are configurable; defaults are `notification.email-verification.requested.v1` and its `.dlt` topic, with 3 partitions, replication factor 1 and 10-minute retention. See [the local setup guide](../docs/development/Email%20Verification%20Local%20Setup.md) for Compose commands, all variables and Docker addresses.

## Tests

```bash
./mvnw clean verify
```

The test profile supplies H2 and fake provider credentials. `EmailDeliveryPropertiesTest` validates configuration, and `EmailKafkaConfigTest` uses embedded Kafka to check delayed offset commit, bounded retries and durable dead-letter publication. No test sends email to SendGrid.

`EmailHistoryRepositoryTest` applies the real Flyway migration with Hibernate schema validation and covers persistence, timestamps, optional expiration, paginated history, duplicate Kafka/webhook IDs, out-of-order delivery events, status/attempt constraints and foreign keys. It uses an isolated H2 schema by default. To verify against a disposable PostgreSQL instance:

```bash
./mvnw -o -Dtest=EmailHistoryRepositoryTest \
  -Dnotification.test.database-url=jdbc:postgresql://localhost:15437/notification_test \
  -Dnotification.test.database-username=notification_test \
  -Dnotification.test.database-password=notification_test_password test
```

Entity unit tests verify UUID identity, equality/hash stability when audit fields change, and exclusion of the lazy relationship from `toString`. Persistence tests verify inherited audit timestamps on insert/update. `EmailHistoryMigrationTest` verifies upgrading V1 to V3 preserves existing delivery rows, backfills audit fields and adds the nullable retry schedule.

The persistence tests create and remove only their own randomly named schemas. Use a dedicated test database.

For step 10 migration and row-lock checks, include `EmailHistoryMigrationTest,EmailHistoryRepositoryTest,EmailNotificationHistoryServiceTest` in `-Dtest`. The pipeline suite uses a local HTTP stub and embedded Kafka. Non-Kafka tests disable listener startup; the dedicated Kafka suites enable their required listeners explicitly.

`SendGridWebhookServiceTest` checks signature and input rejection before repository access. `SendGridWebhookIntegrationTest` uses generated EC keys and the real Flyway/JPA schema to cover HTTP authentication, batch rollback, concurrent duplicates, overlapping batches, out-of-order history and webhook arrival while submission is still `PROCESSING`. Gateway tests verify raw body/header forwarding and protection of every other method/path, with CSRF enabled and disabled. No webhook test contacts SendGrid.
