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
| event_order | BIGINT | GENERATED ALWAYS AS IDENTITY, UNIQUE | UNIQUE | Tie breaker for events with equal timestamps |
| channel_id | UUID | FK, NOT NULL | INDEX | Channel ID |
| user_id | UUID | NOT NULL | INDEX | User ID from User Service |
| action | VARCHAR(20) | NOT NULL | - | `BAN, UNBAN` |
| ban_type | VARCHAR(30) | NOT NULL | - | `CHAT_BAN, LIVE_VIEW_BAN, FULL_BAN` |
| performed_by | UUID | NOT NULL | - | User ID who performed the action |
| reason | VARCHAR(500) | - | - | Action reason |
| created_at | TIMESTAMP | NOT NULL | - | Event time |

Composite Index:
- `INDEX (channel_id, user_id, ban_type, created_at DESC, event_order DESC)`

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

Thiết kế cập nhật theo migration **V4** (thumbnail dispatch) và **V5** ([Refactor livestream: Stream Key cố định](../implement-plan/Streaming%20Persistent%20Key%20Refactor%20Plan.md)): Stream Service quản lý cấu hình phát cố định theo kênh (`channel_stream_configs`), từng thế hệ credential mã hóa (`channel_stream_credentials`), phiên phát tách playback (`stream_sessions`), lịch sử trạng thái (`stream_status_history`), hàng đợi/tiến trình media bền vững (`stream_media_operations`) và runtime state của MediaMTX (`stream_media_runtime_state`). Migrations V1–V4 được giữ nguyên làm lịch sử schema; V5 chặn migration nếu còn session `READY`/`LIVE`/`RECONNECTING` đang hoạt động.

#### 3.1.1 channel_stream_configs

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| channel_id | UUID | PK, NOT NULL | Primary | Channel ID from Channel Service |
| streamer_id | UUID | UNIQUE, NOT NULL | UNIQUE | Streamer ID from User Service |
| owner_user_id | UUID | UNIQUE, NOT NULL | UNIQUE | Owner user identifier |
| title | VARCHAR(255) | NOT NULL | - | Saved channel stream title |
| category_id | UUID | - | - | Saved category ID (nullable while `DRAFT`) |
| visibility | VARCHAR(30) | NOT NULL, DEFAULT `'PUBLIC'` | - | Fixed `'PUBLIC'` |
| status | VARCHAR(30) | NOT NULL | - | `DRAFT, PROVISIONING, ACTIVE, ROTATING, BLOCKED` |
| current_ingest_key_id | UUID | UNIQUE | UNIQUE | Active/provisioning ingest key UUID |
| current_credential_generation | BIGINT | NOT NULL, DEFAULT `0` | - | Monotonic credential generation counter (`>= 0`) |
| media_revision | BIGINT | NOT NULL, DEFAULT `0` | - | Media intent revision counter (`>= 0`) |
| blocked_reason | VARCHAR(500) | - | - | Non-null reason only when `status = 'BLOCKED'` |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints:

- `UNIQUE (owner_user_id)`, `UNIQUE (streamer_id)`, `UNIQUE (current_ingest_key_id)`
- `CHECK (status = 'DRAFT' OR length(btrim(title)) > 0)`
- `CHECK (visibility = 'PUBLIC')`
- `CHECK (status IN ('DRAFT', 'PROVISIONING', 'ACTIVE', 'ROTATING', 'BLOCKED'))`
- `CHECK (status = 'BLOCKED' OR blocked_reason IS NULL)`
- `CHECK ((status IN ('DRAFT', 'BLOCKED') AND current_ingest_key_id IS NULL AND current_credential_generation = 0) OR (status <> 'DRAFT' AND category_id IS NOT NULL AND current_ingest_key_id IS NOT NULL AND current_credential_generation > 0))`

#### 3.1.2 channel_stream_credentials

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Credential row ID |
| channel_id | UUID | FK, NOT NULL | INDEX Composite | References `channel_stream_configs(channel_id)`, ON DELETE CASCADE |
| generation | BIGINT | NOT NULL | UNIQUE Composite | Positive generation (`> 0`) |
| ingest_key_id | UUID | UNIQUE, NOT NULL | UNIQUE | Random ingest UUID for `ingest/{ingest_key_id}` |
| status | VARCHAR(30) | NOT NULL | INDEX Composite | `PENDING, ACTIVE, REVOKED` |
| encrypted_publish_jwt | TEXT | NOT NULL | - | AES-256-GCM encrypted permanent Publish JWT (`ingest/{ingest_key_id}`) |
| encrypted_relay_read_jwt | TEXT | NOT NULL | - | AES-256-GCM encrypted internal permanent Relay Read JWT (`ingest/{ingest_key_id}`) |
| issued_at | TIMESTAMP | NOT NULL | - | Credential issuance timestamp |
| activated_at | TIMESTAMP | - | - | Activation timestamp (required when `ACTIVE`) |
| revoked_at | TIMESTAMP | - | - | Revocation timestamp (required when `REVOKED`) |
| revocation_reason | VARCHAR(500) | - | - | Reason when revoked |
| created_at | TIMESTAMP | NOT NULL | - | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints & Indexes:

- `UNIQUE (channel_id, generation)`, `UNIQUE (ingest_key_id)`
- `UNIQUE INDEX uq_channel_stream_credentials_non_revoked ON channel_stream_credentials (channel_id) WHERE status IN ('PENDING', 'ACTIVE')`
- `INDEX idx_channel_stream_credentials_channel_status ON channel_stream_credentials (channel_id, status, generation DESC)`
- Credential `REVOKED` không bao giờ được kích hoạt lại.

#### 3.1.3 stream_sessions

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Stream session ID, playback path `live/{id}` |
| channel_id | UUID | NOT NULL | INDEX | Channel ID from Channel Service |
| streamer_id | UUID | NOT NULL | INDEX | Streamer ID from User Service |
| owner_user_id | UUID | NOT NULL | INDEX | Owner user identifier |
| category_id | UUID | NOT NULL | INDEX | Category ID from Channel Service |
| title | VARCHAR(255) | NOT NULL | - | Stream title snapshot, non-blank |
| visibility | VARCHAR(30) | NOT NULL | INDEX | `PUBLIC, PRIVATE, UNLISTED` (`PUBLIC` used in MVP) |
| status | VARCHAR(30) | NOT NULL | INDEX | V5 active/new states: `LIVE, RECONNECTING, ENDED` (`READY` history forbidden for new inserts) |
| playback_path | VARCHAR(500) | UNIQUE, NOT NULL | UNIQUE | HLS playback path `/live/{id}/index.m3u8` |
| creation_request_id | UUID | - | UNIQUE Composite | Legacy nullable request ID (kept nullable after V5) |
| ingest_key_id | UUID | FK | Partial INDEX | References `channel_stream_credentials(ingest_key_id)` (nullable for legacy history) |
| media_path_state | VARCHAR(30) | NOT NULL | - | Playback path state: `OPEN, CLOSED` |
| media_source_id | UUID | - | - | Active OBS ingest publisher connection UUID |
| media_source_type | VARCHAR(30) | - | - | `rtmpConn, rtmpsConn` |
| disconnected_at | TIMESTAMP | - | - | Timestamp when OBS publisher disconnected |
| reconnect_deadline_at | TIMESTAMP | - | Partial INDEX | Reconnect grace deadline (`occurredAt + 60s`) |
| started_at | TIMESTAMP | - | INDEX | Actual stream first start time |
| ended_at | TIMESTAMP | - | - | Stream end time |
| end_requested | BOOLEAN | NOT NULL, DEFAULT `FALSE` | - | Prevents delayed ONLINE from reviving closing/resetting sessions |
| end_requested_at | TIMESTAMP | - | - | Timestamp when closure/reset requested session end |
| end_reason | VARCHAR(500) | - | - | Reason for requested closure |
| thumbnail_url | VARCHAR(1000) | - | - | Current R2 thumbnail URL (V4) |
| thumbnail_object_key | VARCHAR(500) | - | - | Current R2 thumbnail object key (V4) |
| thumbnail_updated_at | TIMESTAMP | - | Partial INDEX | Last successful thumbnail commit time (V4) |
| thumbnail_job_id | UUID | - | - | Latest thumbnail job ID (V4) |
| thumbnail_job_status | VARCHAR(30) | - | Partial INDEX | `PENDING_DISPATCH, ACCEPTED, SUCCEEDED, FAILED` (V4) |
| thumbnail_job_deadline_at | TIMESTAMP | - | Partial INDEX | Waiting thumbnail job deadline (V4) |
| created_at | TIMESTAMP | NOT NULL | INDEX | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints & Indexes:

- `UNIQUE (owner_user_id, creation_request_id)` (multiple `NULL`s allowed in PostgreSQL)
- `CHECK (length(btrim(title)) > 0)`, `CHECK (length(btrim(playback_path)) > 0)`
- `CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'UNLISTED'))`
- `CHECK (status IN ('LIVE', 'RECONNECTING', 'ENDED'))`
- `CHECK (media_path_state IN ('OPEN', 'CLOSED'))`
- `CHECK (media_source_type IS NULL OR media_source_type IN ('rtmpConn', 'rtmpsConn'))`
- `CHECK (status <> 'RECONNECTING' OR (disconnected_at IS NOT NULL AND reconnect_deadline_at IS NOT NULL))`
- `CHECK (status <> 'LIVE' OR (started_at IS NOT NULL AND media_source_id IS NOT NULL AND media_source_type IS NOT NULL AND reconnect_deadline_at IS NULL))`
- `CHECK ((status = 'ENDED' AND ended_at IS NOT NULL) OR (status <> 'ENDED' AND ended_at IS NULL))`
- `CHECK ((status = 'LIVE' AND media_path_state = 'OPEN') OR (status = 'RECONNECTING' AND media_path_state IN ('OPEN', 'CLOSED')) OR (status = 'ENDED' AND media_path_state = 'CLOSED'))`
- `CHECK ((end_requested = FALSE AND end_requested_at IS NULL AND end_reason IS NULL) OR (end_requested = TRUE AND end_requested_at IS NOT NULL))`
- `UNIQUE INDEX uq_stream_sessions_channel_slot ON stream_sessions (channel_id) WHERE status IN ('LIVE', 'RECONNECTING')`
- `INDEX idx_stream_sessions_ingest_key_id ON stream_sessions (ingest_key_id) WHERE ingest_key_id IS NOT NULL`
- Thumbnail partial indexes (V4): `idx_stream_sessions_thumbnail_due`, `idx_stream_sessions_thumbnail_pending_dispatch`, `idx_stream_sessions_thumbnail_job_deadline`

Quy tắc khóa & vòng đời (V5):

- Thứ tự khóa chuẩn cho mọi thao tác lifecycle/media: khóa hàng `channel_stream_configs` trước (`PESSIMISTIC_WRITE`), sau đó mới khóa `stream_sessions`; không giữ database transaction mở trong lúc gọi HTTP sang MediaMTX hoặc User/Channel Service.
- Không tạo session trước ở `READY`: session mới được ghi `LIVE` chỉ sau khi xác nhận `ingest/{ingestKeyId}` đang phát, hậu kiểm eligibility và `live/{sessionId}` đã sẵn sàng.
- `media_path_state` phản ánh playback path `live/{sessionId}` (có thể đóng tạm `CLOSED` trong khi `RECONNECTING`).

#### 3.1.4 stream_status_history

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Stream status history ID |
| stream_id | UUID | FK, NOT NULL | INDEX | References `stream_sessions.id`, ON DELETE CASCADE |
| previous_status | VARCHAR(30) | - | - | Previous status (`READY, LIVE, RECONNECTING, ENDED`) |
| status | VARCHAR(30) | NOT NULL | - | New status (`READY, LIVE, RECONNECTING, ENDED`) |
| reason | VARCHAR(500) | - | - | Reason for status change |
| media_source_id | UUID | - | - | Media publisher connection identifier |
| media_event_id | UUID | - | UNIQUE Partial | Media callback event ID for deduplication |
| created_at | TIMESTAMP | NOT NULL | INDEX | Transition time |

Constraints & Indexes:

- `CHECK (previous_status IS NULL OR previous_status IN ('READY', 'LIVE', 'RECONNECTING', 'ENDED'))`
- `CHECK (status IN ('READY', 'LIVE', 'RECONNECTING', 'ENDED'))`
- `INDEX (stream_id, created_at ASC, id ASC)`
- `UNIQUE INDEX uq_stream_status_history_media_event_id ON stream_status_history (media_event_id) WHERE media_event_id IS NOT NULL`

#### 3.1.5 stream_media_operations

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | UUID | PK, NOT NULL | Primary | Durable operation ID |
| channel_id | UUID | FK, NOT NULL | INDEX Composite | References `channel_stream_configs(channel_id)`, ON DELETE CASCADE |
| credential_generation | BIGINT | NOT NULL | - | Generation fencing value (`>= 0`) |
| media_revision | BIGINT | NOT NULL | - | Media revision fencing value (`>= 0`) |
| operation_type | VARCHAR(40) | NOT NULL | Partial UNIQUE | `PROVISION_INGEST, PROCESS_ONLINE, PROCESS_OFFLINE, CLOSE_PLAYBACK, END_SESSION, RESET_KEY` |
| status | VARCHAR(30) | NOT NULL | Partial UNIQUE/INDEX | `PENDING, RUNNING, SUCCEEDED, CANCELLED` |
| target_session_id | UUID | - | - | Target playback session UUID |
| candidate_session_id | UUID | - | Partial UNIQUE | Fixed session UUID pre-allocated for new session creation retry safety |
| target_ingest_key_id | UUID | - | - | Target ingest key UUID |
| target_source_id | UUID | - | - | Target MediaMTX publisher source UUID |
| target_source_type | VARCHAR(30) | - | - | `rtmpConn, rtmpsConn` |
| deduplication_key | VARCHAR(200) | - | UNIQUE Partial | Event UUID, idempotency key, or reconciler dedup key |
| payload | TEXT | - | - | Sanitized JSON metadata (no raw JWTs) |
| last_error | VARCHAR(500) | - | - | Sanitized last failure reason |
| attempt_count | INTEGER | NOT NULL, DEFAULT `0` | - | Retry attempt counter |
| next_attempt_at | TIMESTAMP | NOT NULL | Partial INDEX | Next scheduled retry timestamp |
| lease_until | TIMESTAMP | - | - | Worker lease expiration (`60s`) |
| lease_owner | VARCHAR(100) | - | - | Worker instance identifier holding lease |
| started_at | TIMESTAMP | - | - | First execution start time |
| completed_at | TIMESTAMP | - | - | Completion or cancellation timestamp |
| created_at | TIMESTAMP | NOT NULL | INDEX | Created time |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

Constraints & Indexes:

- `UNIQUE INDEX uq_stream_media_operations_dedup ON stream_media_operations (channel_id, operation_type, deduplication_key) WHERE deduplication_key IS NOT NULL`
- `UNIQUE INDEX uq_stream_media_operations_event_dedup ON stream_media_operations (deduplication_key) WHERE operation_type IN ('PROCESS_ONLINE', 'PROCESS_OFFLINE') AND deduplication_key IS NOT NULL`
- `UNIQUE INDEX uq_stream_media_operations_one_candidate_session ON stream_media_operations (channel_id) WHERE candidate_session_id IS NOT NULL AND status IN ('PENDING', 'RUNNING')`
- `UNIQUE INDEX uq_stream_media_operations_active_provision ON stream_media_operations (channel_id) WHERE operation_type = 'PROVISION_INGEST' AND status IN ('PENDING', 'RUNNING')`
- `UNIQUE INDEX uq_stream_media_operations_active_reset ON stream_media_operations (channel_id) WHERE operation_type = 'RESET_KEY' AND status IN ('PENDING', 'RUNNING')`
- `INDEX idx_stream_media_operations_due ON stream_media_operations (next_attempt_at ASC, created_at ASC, id ASC) WHERE status IN ('PENDING', 'RUNNING')`

#### 3.1.6 stream_media_runtime_state

| Column | Type | Constraints | Index | Description |
|---|---|---|---|---|
| id | VARCHAR(50) | PK, NOT NULL | Primary | Singleton key for MediaMTX runtime state |
| server_started_at | VARCHAR(100) | - | - | Last observed MediaMTX server startup timestamp from Control API |
| last_reconciled_at | TIMESTAMP | - | - | Last completed reconciliation pass timestamp |
| updated_at | TIMESTAMP | NOT NULL | - | Last updated time |

### 3.2 Relationships

- `channels (Channel Service)` 1 — 1 `channel_stream_configs`
- `streamers (User Service)` 1 — 1 `channel_stream_configs`
- `channel_stream_configs` 1 — N `channel_stream_credentials`
- `channel_stream_configs` 1 — N `stream_media_operations`
- `channels (Channel Service)` 1 — N `stream_sessions`
- `streamers (User Service)` 1 — N `stream_sessions`
- `categories (Channel Service)` 1 — N `stream_sessions`
- `channel_stream_credentials` 1 — N `stream_sessions` (via nullable `ingest_key_id`)
- `stream_sessions` 1 — N `stream_status_history`

---

## 4. Chat Service Data Model

### 4.1 chat_messages

| Field | Type | Required | Description |
|---|---|---|---|
| _id | UUID string | YES | Server message ID |
| stream_id | UUID string | YES | Session ID; one chat room per session |
| channel_id | UUID string | YES | Channel resolved from Stream Service |
| user_id | UUID string | YES | Authenticated sender |
| client_message_id | UUID string | YES | Stable retry identifier per sender and session |
| sequence | NUMBER (safe integer) | YES | Monotonic per-session ordering and cursor key |
| content | STRING | YES | Trimmed text, 1–500 Unicode code points |
| sender_snapshot.display_name | STRING | YES | Name observed by the server when accepting the message |
| sender_snapshot.avatar_url | STRING / null | NO | Retained avatar URL at send time |
| created_at | DATETIME | YES | Server timestamp; sequence orders equal timestamps |

Indexes: default `_id`, unique `(stream_id, sequence)`, unique `(stream_id, user_id, client_message_id)`.

### 4.2 chat_counters

One document per session: `_id` is the session UUID string, `value` is the latest committed sequence. Counter increment and message insert share a MongoDB transaction. Replica set required; application startup does not auto-create indexes.

### 4.3 Access and retention

Chat is readable/writable only during PUBLIC/LIVE or PUBLIC/RECONNECTING, subject to account and channel access. History uses cursor pagination, 50 messages per request. There is no VOD chat replay or offset_ms requirement. No retention TTL is configured yet; ending a stream closes access but does not delete stored messages. User Service retains previous committed avatar objects for historical snapshots.

See [Chat contract](../contracts/chat/README.md). Cross-service identifiers are logical references; no MongoDB join to PostgreSQL or foreign key across services.

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
