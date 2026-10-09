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

These packages are scaffolding for later implementation steps. No Kafka listener, HTTP endpoint, SendGrid client or database migration is registered yet. The webhook is an endpoint in `email.controller`, with its business logic in `email.service` / `email.service.impl`.

`email.configuration` now prepares validated email/provider settings, request/dead-letter topics and bounded Kafka error handling. The future listener uses record acknowledgements, retries twice by default, and acknowledges a failed message only after its dead-letter copy is accepted by Kafka. These are infrastructure beans; the delivery listener and email business logic remain for later steps.

Notification does not check OTPs, query the User database, update `emailVerified`, or grant roles. Email delivery results remain in this service; User Service does not wait for webhook events. The Kafka wire DTO is defined in [the Kafka contract](../docs/contracts/Email%20Verification%20Kafka.md). SendGrid integration and webhook authentication will be implemented in their respective steps.

## SendGrid DTOs

- `email.client.sendgrid.dto.request.SendGridMailRequest`: Dynamic Template payload with sender, `template_id`, recipients, OTP template data and `custom_args`. Its nested classes model email addresses, personalizations and OTP data; OTP is excluded from `toString()`.
- `email.client.sendgrid.dto.response.SendGridErrorResponse`: provider `errors` array, retaining the message and optional field while ignoring unused metadata.
- `email.dto.request.SendGridWebhookEventRequest`: one event in the incoming webhook array, with root-level `eventId` correlation, provider IDs, Unix timestamp in seconds and optional delivery details. Unknown provider fields are ignored.

Template selection belongs to Notification configuration. These DTOs prepare the Dynamic Template flow without fetching the template list. A successful Mail Send response has HTTP 202 and no JSON body, so no success-body DTO is defined. The future client will read `X-Message-ID` from response headers. The future webhook handler must verify the signature against the original raw body before deserializing events.

DTO tests cover SendGrid field names, OTP leading zeros and exclusion from `toString()`, optional sender names, error metadata, and webhook arrays with missing/extra fields. They do not call SendGrid.

## Configuration and local run

Development settings are in `application-dev.yaml`; activate the `dev` profile when running locally. Notification listens on port `8085` with context path `/notification`. It connects to its own PostgreSQL on host port `5437` by default, uses Flyway and validates JPA mappings. History tables and migrations are added with delivery persistence. Redis is not currently used, so its health contributor is disabled.

Keep `NOTIFICATION_DB_PASSWORD`, Kafka connection settings and the required `SENDGRID_API_KEY`, `SENDGRID_FROM_EMAIL`, and `SENDGRID_WEBHOOK_PUBLIC_KEY` in `notification-service/.env`, then export them or configure the IDE environment before starting. The root `.env.example` is for Docker Compose infrastructure only. Invalid or missing email/provider configuration fails startup. Do not commit real credentials. Sender name defaults to `RealLive`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Start Notification before User's future email publisher: Notification provisions both topics through KafkaAdmin, which fails startup if Kafka is unavailable. Topic names are configurable; defaults are `notification.email-verification.requested.v1` and its `.dlt` topic, with 3 partitions, replication factor 1 and 10-minute retention. See [the local setup guide](../docs/development/Email%20Verification%20Local%20Setup.md) for Compose commands, all variables and Docker addresses.

## Tests

```bash
./mvnw clean verify
```

The test profile supplies H2 and fake provider credentials. `EmailDeliveryPropertiesTest` validates configuration, and `EmailKafkaConfigTest` uses embedded Kafka to check delayed offset commit, bounded retries and durable dead-letter publication. No test sends email to SendGrid.
