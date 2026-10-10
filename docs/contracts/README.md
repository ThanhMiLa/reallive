# Shared Event Contracts

This directory contains versioned example payloads for events exchanged between services. These examples also serve as shared fixtures for serialization and deserialization tests.

| Example payload | Producer | Consumer |
| --- | --- | --- |
| [Email verification requested v1](kafka/email-verification-requested.v1.json) | User Service | Notification Service |

The email verification example contains synthetic test values. Both services' `EmailVerificationRequestedMessageTest` classes read the same file to verify their Kafka message format. Run those tests from each service directory with `./mvnw -Dtest=EmailVerificationRequestedMessageTest test`; the repository's `docs/contracts` directory must be present.
