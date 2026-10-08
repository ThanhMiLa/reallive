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

Notification does not check OTPs, query the User database, update `emailVerified`, or grant roles. Email delivery results remain in this service; User Service does not wait for webhook events. The Kafka wire DTO, SendGrid integration and webhook authentication will be defined in their respective implementation steps.
