# Data Model

## Table of Contents

- [1. User Service Data Model](#1-user-service-data-model)
- [2. Channel Service Data Model](#2-channel-service-data-model)
- [3. Stream Service Data Model](#3-stream-service-data-model)
- [4. Chat Service Data Model](#4-chat-service-data-model)
- [5. Payment Service Data Model](#5-payment-service-data-model)
- [6. VOD Service Data Model](#6-vod-service-data-model)
- [7. Notification Service Data Model](#7-notification-service-data-model)

---

## 1. User Service Data Model

### 1.1 Tables

#### 1.1.1 users
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | User identifier |
| username | VARCHAR(255)| UNIQUE, NOT NULL | UNIQUE | Username Account |
| email | VARCHAR(255) | UNIQUE, NOT NULL | UNIQUE | User email |
| password_hash | VARCHAR(255) | NOT NULL | - | Password hash |
| status | VARCHAR(30) | NOT NULL | INDEX | `ACTIVE, BANNED` |
| auth_revision | BIGINT | NOT NULL, DEFAULT 0 | - | Monotonic revision for committed account authorities/status synchronization |
| email_verified | BOOLEAN | NOT NULL | - | Email verification status |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

#### 1.1.2 user_google_accounts
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Google account mapping identifier |
| user_id | UUID | FK, UNIQUE, NOT NULL | INDEX | References `users.id` |
| google_account_id | VARCHAR(255) | UNIQUE, NOT NULL | UNIQUE | Google subject/account identifier |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

#### 1.1.3 roles
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Role ID |
| name | VARCHAR(255) | UNIQUE, NOT NULL | UNIQUE | Role Name |

#### 1.1.4 user_roles
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| user_id | UUID | FK, NOT NULL | INDEX | User ID |
| role_id | UUID | FK, NOT NULL | INDEX | Role ID |  

Composite Constraint:
- `PRIMARY KEY (user_id, role_id)`

#### 1.1.5 permissions
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Permission ID |
| name | VARCHAR(255) | UNIQUE, NOT NULL | UNIQUE | Permission Name |

#### 1.1.6 role_permissions
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| role_id | UUID | FK, NOT NULL | INDEX | Role ID |
| permission_id | UUID | FK, NOT NULL | INDEX | Permission ID |

Composite Constraint:
- `PRIMARY KEY (role_id, permission_id)`

#### 1.1.8 profiles

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Profile ID |
| user_id | UUID | FK, UNIQUE, NOT NULL | UNIQUE | User ID |
| display_name | VARCHAR(100) | NOT NULL | INDEX | User display name |
| avatar_url | VARCHAR(500) | - | - | User avatar URL |
| dob | DATE | - | - | Date of birth |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

#### 1.1.9 streamers
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Streamer ID |
| user_id | UUID | FK, UNIQUE, NOT NULL | UNIQUE | User ID |
| status | VARCHAR(30) | NOT NULL | INDEX | `PENDING, ACTIVE, SUSPENDED, BANNED`|
| verified_at | TIMESTAMP | - | - | Time when streamer verification was completed |
| suspended_at | TIMESTAMP | - | - | Time when streaming permission was suspended |
| suspended_until | TIMESTAMP | - | INDEX | Suspension expiration time |
| suspension_reason | VARCHAR(500) | - | - | Reason for suspension |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Composite Index:
- `INDEX (status, suspended_until)`

#### 1.1.10 user_category_preferences
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|   
| user_id | UUID | FK, NOT NULL | INDEX | User ID |
| category_id | UUID | NOT NULL | INDEX | Category ID from Channel Service |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |


Composite Constraint:
- `PRIMARY KEY (user_id, category_id)`


#### 1.1.11 auth_state_sync_tasks

`auth_state_sync_tasks` stores durable publication work owned by User Service. A role/status mutation records its task in the same transaction, and a worker retries Redis synchronization after commit. See [Auth State](../contracts/Auth%20State.md).

| Column | Type | Constraints | Description |
|---|---|---|---|
| user_id | UUID | PK, FK `users.id`, ON DELETE CASCADE | Account awaiting synchronization |
| revision | BIGINT | NOT NULL | Auth revision recorded with the mutation |
| attempts | INTEGER | NOT NULL, DEFAULT 0 | Failed publication attempts |
| next_attempt_at | TIMESTAMP | NOT NULL, INDEX | Earliest next retry in UTC |

One task exists per account. Synchronization publishes current committed state and removes completed tasks; it never repeats the original business operation.

### 1.2 Relationships

- `users` 1 — 1 `profiles`
- `users` 1 — 0...1 `user_google_accounts`
- `users` N — N `roles` through `user_roles`
- `roles` N — N `permissions` through `role_permissions`
- `users` 1 — 0...1 `streamers`
- `users` N — N `categories(Channel Service)` through `user_category_preferences`

---

## 2. Channel Service Data Model
### 2.1 Tables

#### 2.1.1 channels
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Channel ID |
| streamer_id | UUID | UNIQUE, NOT NULL | UNIQUE | Streamer ID from User Service |
| user_id | UUID | Nullable for existing rows; required on channel creation | - | Owner user ID from User Service; no cross-service foreign key |
| name | VARCHAR(150) | NOT NULL | INDEX | Channel name |
| slug | VARCHAR(150) | UNIQUE, NOT NULL | UNIQUE | Public channel slug |
| description | VARCHAR(1000) | - | - | Channel description |
| avatar_url | VARCHAR(500) | - | - | Channel avatar URL |
| banner_url | VARCHAR(500) | - | - | Channel banner URL |
| follower_count | BIGINT | NOT NULL, DEFAULT 0 | - | Stored total follower count |
| status | VARCHAR(30) | NOT NULL | INDEX | `ACTIVE, BANNED` |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |
| deleted_at | TIMESTAMP | - | INDEX | Soft delete time |


Constraints:
- `follower_count >= 0`

#### 2.1.2 categories
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Category ID |
| name | VARCHAR(100) | UNIQUE, NOT NULL | UNIQUE | Category name |
| slug | VARCHAR(120) | UNIQUE, NOT NULL | UNIQUE | Public category slug |
| thumbnail_url | VARCHAR(500) | - | - | Category thumbnail image |
| status | VARCHAR(30) | NOT NULL | INDEX | `ACTIVE, INACTIVE` |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

#### 2.1.3 channel_categories
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| channel_id | UUID | FK, NOT NULL | INDEX | Channel ID |
| category_id | UUID | FK, NOT NULL | INDEX | Category ID |
| created_at | TIMESTAMP | NOT NULL | - | Created time |

Composite Constraint:
- `PRIMARY KEY (channel_id, category_id)`

#### 2.1.4 channel_followers
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| channel_id | UUID | FK, NOT NULL | INDEX | Channel ID |
| user_id | UUID | NOT NULL | INDEX | User ID from User Service |
| followed_at | TIMESTAMP | NOT NULL | - | Follow time |

Composite Constraint:
- `PRIMARY KEY (channel_id, user_id)`

#### 2.1.5 channel_moderators
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| channel_id | UUID | FK, NOT NULL | INDEX | Channel ID |
| user_id | UUID | NOT NULL | INDEX | User ID from User Service |
| assigned_at | TIMESTAMP | NOT NULL | - | Moderator assignment time |

Composite Constraint:
- `PRIMARY KEY (channel_id, user_id)`

#### 2.1.6 report_categories
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Report category ID |
| name | VARCHAR(100) | UNIQUE, NOT NULL | UNIQUE | Report category name |
| description | VARCHAR(500) | | - | Category description |
| status | VARCHAR(30) | NOT NULL | INDEX | `ACTIVE, INACTIVE` |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

#### 2.1.7 channel_reports
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Report ID |
| channel_id | UUID | FK, NOT NULL | INDEX | Reported channel ID |
| reporter_user_id | UUID | NOT NULL | INDEX | Reporter user ID |
| report_category_id | UUID | FK, NOT NULL | INDEX | Report category ID |
| response | JSONB | - | - | Report note and evidence metadata |
| status | VARCHAR(30) | NOT NULL | INDEX | `PENDING, UNDER_REVIEW, ACCEPTED, REJECTED` |
| reviewed_by | UUID | - | INDEX | Reviewer user ID |
| reviewed_at | TIMESTAMP | - | - | Review time |
| resolution_note | VARCHAR(1000) | - | - | Resolution note |
| created_at | TIMESTAMP | NOT NULL | INDEX | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

#### 2.1.8 channel_user_bans
| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Ban event ID |
| channel_id | UUID | FK, NOT NULL | INDEX | Channel ID |
| user_id | UUID | NOT NULL | INDEX | User ID from User Service |
| action | VARCHAR(20) | NOT NULL | - | `BAN, UNBAN` |
| ban_type | VARCHAR(30) | NOT NULL | - | `CHAT_BAN, LIVE_VIEW_BAN, FULL_BAN` |
| performed_by | UUID | NOT NULL | - | User ID who performed the action |
| reason | VARCHAR(500) | - | - | Action reason |
| created_at | TIMESTAMP | NOT NULL | - | Event time |

Composite Index:
- `INDEX (channel_id, user_id, ban_type, created_at)`

### 2.2 Relationships

- `streamers (User Service)` 1 — 1 `channels`
- `channels` N — N `categories` through `channel_categories`
- `users (User Service)` N — N `channels` through `channel_followers`
- `users (User Service)` N — N `channels` through `channel_moderators`
- `users (User Service)` N — N `channels` through `channel_reports`
- `users (User Service)` N — N `channels` through `channel_bans`

---

## 3. Stream Service Data Model

### 3.1 Tables

#### 3.1.1 stream_sessions

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Stream session ID |
| channel_id | UUID | NOT NULL | INDEX | Channel ID from Channel Service |
| streamer_id | UUID | NOT NULL | INDEX | Streamer ID from User Service |
| category_id | UUID | NOT NULL | INDEX | Category ID from Channel Service |
| title | VARCHAR(255) | NOT NULL | - | Stream title |
| visibility | VARCHAR(30) | NOT NULL | INDEX | `PUBLIC, PRIVATE, UNLISTED` |
| status | VARCHAR(30) | NOT NULL | INDEX | `READY, LIVE, RECONNECTING, ENDED` |
| playback_path | VARCHAR(500) | NOT NULL | - | HLS playback path |
| started_at | TIMESTAMP | - | INDEX | Actual stream start time |
| ended_at | TIMESTAMP | - | INDEX | Stream end time |
| created_at | TIMESTAMP | NOT NULL | INDEX | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

#### 3.1.2 stream_status_history

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Stream status history ID |
| stream_id | UUID | FK, NOT NULL | INDEX | Stream session ID |
| status | VARCHAR(30) | NOT NULL | INDEX | `CREATED, LIVE, RECONNECTING, ENDED` |
| reason | VARCHAR(500) | - | - | Reason for status change |
| created_at | TIMESTAMP | NOT NULL | INDEX | Event time |

### 3.2 Relationships

- `channels (Channel Service)` 1 — N `stream_sessions`
- `streamers (User Service)` 1 — N `stream_sessions`
- `categories (Channel Service)` 1 — N `stream_sessions`
- `stream_sessions` 1 — N `stream_status_history`

---

## 4. Chat Service Data Model

### 4.1 Collections
| Field | Type | Required | Index | Description |
|---|---|---|---|---|
| _id | UUID | YES | Primary | Chat message identifier |
| stream_id | UUID | YES | INDEX | Stream session ID from Stream Service |
| channel_id | UUID | YES | INDEX | Channel ID from Channel Service |
| user_id | UUID | YES | INDEX | Sender user ID from User Service |
| content | STRING | YES | - | Chat message content |
| offset_ms | LONG | YES | COMPOSITE | Message position relative to livestream start time |
| status | STRING | YES | INDEX | `ACTIVE, DELETED` |
| created_at | DATETIME | YES | COMPOSITE | Message creation time |
| deleted_at | DATETIME | NO | - | Time when message was deleted |
| deleted_by | UUID | NO | - | Moderator / streamer user ID who deleted the message |

### 4.2 Relationships

*TBD*

---

## 5. Payment Service Data Model

### 5.1 Tables

#### 5.1.1 user_wallets

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Wallet ID |
| user_id | UUID | UNIQUE, NOT NULL | UNIQUE | User ID from User Service |
| balance | BIGINT | NOT NULL | - | Current coin balance of the user |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints:
- `balance >= 0`


#### 5.1.2 wallet_transactions

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Wallet transaction ID |
| wallet_id | UUID | FK, NOT NULL | INDEX | Wallet associated with the transaction |
| type | VARCHAR(30) | NOT NULL | INDEX | `DEPOSIT, GIFT_PURCHASE, REFUND` |
| amount | BIGINT | NOT NULL | - | Coin amount involved in the transaction |
| reference_id | UUID | - | INDEX | Related business transaction ID |
| created_at | TIMESTAMP | NOT NULL | INDEX | Transaction time |

Constraints:
- `amount > 0`

#### 5.1.3 payment_transactions

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Payment transaction ID |
| wallet_id | UUID | FK, NOT NULL | INDEX | Wallet receiving coins from the payment |
| order_code | BIGINT | UNIQUE, NOT NULL | UNIQUE | Order code generated by the system and sent to payOS |
| amount | BIGINT | NOT NULL | - | Real-money payment amount |
| coin_amount | BIGINT | NOT NULL | - | Coin amount credited to the wallet after successful payment |
| status | VARCHAR(30) | NOT NULL | INDEX | `PENDING, PAID, CANCELLED` |
| created_at | TIMESTAMP | NOT NULL | INDEX | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |


#### 5.1.4 virtual_gifts

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Virtual gift ID |
| name | VARCHAR(100) | UNIQUE, NOT NULL | UNIQUE | Gift name |
| image_url | VARCHAR(500) | NOT NULL | - | Gift image URL |
| coin_price | BIGINT | NOT NULL | - | Current gift price in coins |
| status | VARCHAR(20) | NOT NULL | INDEX | `ACTIVE, INACTIVE` |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints:
- `coin_price > 0`

#### 5.1.5 donations

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Donation ID |
| donor_user_id | UUID | NOT NULL | INDEX | Donor user ID from User Service |
| streamer_id | UUID | NOT NULL | INDEX | Recipient streamer ID from User Service |
| channel_id | UUID | NOT NULL | INDEX | Channel where the donation occurred |
| stream_id | UUID | NOT NULL | INDEX | Stream session where the donation occurred |
| gift_id | UUID | FK, NOT NULL | INDEX | Donated virtual gift |
| quantity | INT | NOT NULL | - | Number of gifts donated |
| unit_coin_price | BIGINT | NOT NULL | - | Gift coin price at the time of donation |
| streamer_revenue_amount | BIGINT | NOT NULL | - | Revenue credited to the streamer from this donation |
| status | VARCHAR(30) | NOT NULL | INDEX | `COMPLETED, REFUNDED` |
| created_at | TIMESTAMP | NOT NULL | INDEX | Donation time |

Constraints:
- `quantity > 0`
- `unit_coin_price > 0`
- `streamer_revenue_amount >= 0`

#### 5.1.6 donation_refunds

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Donation refund ID |
| donation_id | UUID | FK, UNIQUE, NOT NULL | UNIQUE | Refunded donation |
| refunded_by | UUID | NOT NULL | INDEX | User or admin who performed the refund |
| coin_amount | BIGINT | NOT NULL | - | Coin amount returned to the donor and deducted from the streamer |
| status | VARCHAR(30) | NOT NULL | INDEX | `PENDING, COMPLETED, REJECTED, FAILED` |
| reason | VARCHAR(500) | - | - | Refund reason |
| created_at | TIMESTAMP | NOT NULL | INDEX | Refund creation time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints:
- `coin_amount > 0`


#### 5.1.7 streamer_wallets

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Streamer wallet ID |
| streamer_id | UUID | UNIQUE, NOT NULL | UNIQUE | Streamer ID from User Service |
| available_balance | BIGINT | NOT NULL | - | Revenue currently available for withdrawal |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints:
- `available_balance >= 0`


#### 5.1.8 streamer_revenue_transactions

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Streamer revenue transaction ID |
| streamer_wallet_id | UUID | FK, NOT NULL | INDEX | Streamer wallet associated with the transaction |
| type | VARCHAR(30) | NOT NULL | INDEX | `DONATION, REFUND, WITHDRAWAL` |
| amount | BIGINT | NOT NULL | - | Revenue amount involved in the transaction |
| reference_id | UUID | - | INDEX | Related donation, refund or withdrawal ID |
| created_at | TIMESTAMP | NOT NULL | INDEX | Transaction time |

Constraints:
- `amount > 0`


#### 5.1.9 withdrawal_requests

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Withdrawal request ID |
| streamer_id | UUID | NOT NULL | INDEX | Streamer requesting the withdrawal |
| amount | BIGINT | NOT NULL | - | Requested withdrawal amount |
| status | VARCHAR(30) | NOT NULL | INDEX | `PENDING, APPROVED, PAID, REJECTED, FAILED` |
| payout_method | VARCHAR(30) | NOT NULL | - | Withdrawal payout method |
| payout_details | JSONB | NOT NULL | - | Snapshot of payout destination information |
| reviewed_by | UUID | - | INDEX | Admin who reviewed the request |
| rejection_reason | VARCHAR(500) | - | - | Reason for rejecting the withdrawal request |
| created_at | TIMESTAMP | NOT NULL | INDEX | Requested time |
| processed_at | TIMESTAMP | - | - | Time when the withdrawal was completed or rejected |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints:
- `amount > 0`

### 5.2 Relationships

- `users (User Service)` 1 — 1 `user_wallets`
- `user_wallets` 1 — N `wallet_transactions`
- `user_wallets` 1 — N `payment_transactions`
- `users (User Service)` 1 — N `donations` as donor
- `streamers (User Service)` 1 — N `donations` as recipient
- `channels (Channel Service)` 1 — N `donations`
- `stream_sessions (Stream Service)` 1 — N `donations`
- `virtual_gifts` 1 — N `donations`
- `donations` 1 — 0...1 `donation_refunds`
- `streamers (User Service)` 1 — 1 `streamer_wallets`
- `streamer_wallets` 1 — N `streamer_revenue_transactions`
- `streamers (User Service)` 1 — N `withdrawal_requests`

---

## 6. VOD Service Data Model

### 6.1 Tables

#### 6.1.1 vods

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | VOD ID |
| stream_id | UUID | UNIQUE, NOT NULL | UNIQUE | Original stream session ID from Stream Service |
| channel_id | UUID | NOT NULL | INDEX | Channel ID from Channel Service |
| title | VARCHAR(255) | NOT NULL | - | VOD title copied from the original stream |
| storage_key | VARCHAR(500) | - | - | Object storage key of the recorded video |
| thumbnail_key | VARCHAR(500) | - | - | Object storage key of the generated thumbnail |
| duration_seconds | BIGINT | - | - | Duration of the recorded video in seconds |
| visibility | VARCHAR(30) | NOT NULL | INDEX | `PUBLIC, PRIVATE, UNLISTED` |
| status | VARCHAR(30) | NOT NULL | INDEX | `PROCESSING, READY, PUBLISHED, FAILED` |
| created_at | TIMESTAMP | NOT NULL | INDEX | VOD creation time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints:
- `duration_seconds >= 0`

### 6.2 Relationships

- `stream_sessions (Stream Service)` 1 — 0...1 `vods`
- `channels (Channel Service)` 1 — N `vods`

---

## 7. Notification Service Data Model

### 7.1 Tables

#### 7.1.1 email_notifications

One row represents an email request received from Kafka. All email types share this table.

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Internal notification identifier |
| event_id | UUID | UNIQUE, NOT NULL | UNIQUE | Kafka event identifier used for deduplication and webhook correlation |
| user_id | UUID | NOT NULL | Composite | Recipient user identifier; no cross-service foreign key |
| email_type | VARCHAR(50) | NOT NULL | - | Email purpose; currently `EMAIL_VERIFICATION`, extended as other email flows are implemented |
| recipient_email | VARCHAR(255) | NOT NULL | - | Recipient email address |
| template_id | VARCHAR(255) | - | - | SendGrid template selected for sending |
| status | VARCHAR(30) | NOT NULL, DEFAULT `PENDING` | - | `PENDING, PROCESSING, ACCEPTED, FAILED, EXPIRED, UNKNOWN` |
| attempt_count | INTEGER | NOT NULL, DEFAULT 0, CHECK >= 0 | - | Number of claimed provider attempts; an attempt claimed before a crash also counts |
| next_attempt_at | TIMESTAMPTZ | Nullable | - | Earliest allowed provider retry; cleared on claim or terminal status |
| last_http_status | SMALLINT | - | - | HTTP status of the latest provider submission |
| last_error | TEXT | - | - | Latest sanitized error; exclude OTPs, credentials and full request bodies |
| provider_message_id | TEXT | - | - | Message identifier returned by the provider |
| issued_at | TIMESTAMPTZ | NOT NULL | - | Time the originating service issued the request |
| expires_at | TIMESTAMPTZ | - | - | Request expiration; nullable for emails without a deadline |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Composite | Notification creation time |
| updated_at | TIMESTAMPTZ | NOT NULL, DEFAULT CURRENT_TIMESTAMP | - | Last update time; maintained by the application on updates |

Composite index:
- `INDEX (user_id, created_at)`

`ACCEPTED` describes provider submission, not email delivery. Delivery history is stored separately below. Do not store OTPs, OTP hashes, password-reset links or full Kafka/SendGrid payloads. There is no `challenge_id` or `metadata` column in this version. Type-specific template data remains in transport DTOs. Migration V3 adds nullable `next_attempt_at` without changing existing history. Submission workers use a short row-lock transaction to claim an attempt and check its number before persisting a result.

#### 7.1.2 email_delivery_events

One row represents one SendGrid webhook event. Store separate rows for each event, including events received out of order.

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Internal delivery event identifier |
| notification_id | UUID | FK, NOT NULL | Composite | References `email_notifications.id` |
| sg_event_id | TEXT | UNIQUE when non-null | UNIQUE | SendGrid event identifier used to deduplicate webhook redelivery |
| sg_message_id | TEXT | - | - | SendGrid message identifier from the webhook |
| event_type | VARCHAR(50) | NOT NULL | - | `EmailDeliveryEventType` stored with `EnumType.STRING`: `PROCESSED, DELIVERED, DEFERRED, BOUNCE, DROPPED` |
| provider_timestamp | TIMESTAMPTZ | NOT NULL | Composite | Provider event time, converted from Unix seconds |
| received_at | TIMESTAMPTZ | NOT NULL, DEFAULT CURRENT_TIMESTAMP | - | Time Notification received the event |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT CURRENT_TIMESTAMP | - | Persistence creation time inherited from BaseEntity |
| updated_at | TIMESTAMPTZ | NOT NULL, DEFAULT CURRENT_TIMESTAMP | - | Last persistence update time inherited from BaseEntity |
| response | TEXT | - | - | Sanitized mail-server response |
| reason | TEXT | - | - | Sanitized provider failure reason |
| smtp_status | VARCHAR(50) | - | - | Webhook `status`, distinct from HTTP submission status |

Composite index:
- `INDEX (notification_id, provider_timestamp)`

Resolve the webhook's `eventId` against `email_notifications.event_id`, then persist the related `notification_id`. No recipient email or user ID is duplicated in this table. Multiple events without `sg_event_id` are allowed; uniqueness applies when the provider supplies an ID.

Both email entities inherit `created_at` and `updated_at` from Notification's `common.entity.BaseEntity`, using `Instant` in Java. Migration V2 adds these audit columns to existing delivery history and backfills both from `received_at`; provider and receipt timestamps remain separate.

Migration V4 converts existing lowercase delivery event types to uppercase enum names. The webhook still receives SendGrid's lowercase values and maps the five supported delivery types to `EmailDeliveryEventType`; unrelated or unknown types are ignored.

### 7.2 Relationships

- `email_notifications` 1 — N `email_delivery_events` through `notification_id`.
- `user_id` identifies a user owned by User Service; Notification does not query that database or enforce a cross-service foreign key.
- Email history does not modify email verification, account status or streamer privileges.
