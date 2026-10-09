# Email verification Kafka contract

Topic: `notification.email-verification.requested.v1` (configurable). The Kafka key is the UUID `userId` as a string. The value is JSON, with no Java class name or package-specific type headers.

User Service publishes `user.dto.message.EmailVerificationRequestedMessage`. Notification Service deserializes it into its own `email.dto.message.EmailVerificationRequestedMessage`. Each service builds independently; the shared [example JSON](email-verification-requested.v1.json) is the compatibility fixture used by both modules' contract tests.

| Field | JSON type | Meaning |
|---|---|---|
| `schemaVersion` | integer | Contract version; version 1 is defined here |
| `eventId` | UUID string | Email request identity; reused for retries of the same request |
| `challengeId` | UUID string | Correlation identity of the OTP issuance; not stored in Redis |
| `userId` | UUID string | Authenticated user identity, also used as the Kafka key |
| `emailType` | string | `EMAIL_VERIFICATION`; Notification selects the configured template |
| `recipientEmail` | string | Recipient address read from the User database |
| `otp` | string | Exactly six ASCII digits, preserving leading zeros |
| `issuedAt` | ISO-8601 UTC string | OTP issuance time |
| `expiresAt` | ISO-8601 UTC string | OTP expiry time, strictly after issuance |

All fields are required. The future consumer must validate the schema version, fields and expiry before sending email. This document defines the DTOs and their JSON representation. User Service implements OTP requests and Kafka publication; Notification consumer validation and delivery are implemented in subsequent steps.

The message contains the original OTP because Notification needs it to send the email. Redis stores only the HMAC string at `otp:email-verification:cooldown:{userId}` with a 60-second TTL, shared by OTP validity and resend cooldown. The hash is not part of this message. `expiresAt` must use the same lifetime. Both message DTOs exclude OTP from `toString()`. Callers must not log raw JSON or OTP values, including on retries and dead-letter handling.

Notification owns the SendGrid template ID, sender and provider credentials. It places OTP into `dynamic_template_data` and `eventId` into `custom_args`. User Service does not publish template IDs or SendGrid payloads.
