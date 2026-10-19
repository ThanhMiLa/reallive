# Channel Service

Java 21 / Spring Boot service owning channel profiles, categories and their relationships.

## Persistence foundation

```text
com.reallive.channelservice/
├── ChannelServiceApplication.java
├── common/entity/BaseEntity.java
└── channel/
    ├── entity/ChannelEntity.java
    ├── entity/enums/ChannelStatus.java
    ├── entity/CategoryEntity.java
    ├── entity/enums/CategoryStatus.java
    ├── entity/ChannelCategoryEntity.java
    ├── entity/ChannelCategoryId.java
    ├── entity/ChannelFollowerEntity.java
    ├── entity/ChannelFollowerId.java
    ├── repository/ChannelRepository.java
    ├── repository/CategoryRepository.java
    └── repository/ChannelCategoryRepository.java
```

Flyway migration `V1__create_channels_and_categories_tables.sql` implements Data Model sections 2.1.1–2.1.3. Hibernate validates the schema instead of creating it. Channels and categories share creation/update timestamps through the service-local `BaseEntity`. Status is required explicitly: channels use `ACTIVE`/`BANNED`, categories use `ACTIVE`/`INACTIVE`.

`channels.streamer_id` is a unique external UUID owned by User Service, without a cross-service foreign key or entity relationship. The channel slug and category name/slug are also unique. Unique constraints provide their own indexes; the composite primary key already indexes `channel_categories.channel_id`, and a separate index supports category lookups.

All channel and category persistence code belongs to the `channel` business module; shared audit fields belong to `common.entity`.

`ChannelCategoryEntity` maps the composite `(channel_id, category_id)` key with `@EmbeddedId` and `@MapsId`, lazy parent relationships and a creation timestamp only. `ChannelCategoryId` is an embeddable identifier value type used by JPA and repository ID operations. The association does not inherit `BaseEntity`, since the data model has no `updated_at` column for this table. There is no cascading parent deletion: remove category links explicitly before physically deleting a channel or category.

Flyway migration `V2__add_follower_count_and_channel_followers.sql` adds `channels.follower_count` as a nonnegative `BIGINT NOT NULL DEFAULT 0`, initializing existing channels to zero, and creates the follower table from Data Model section 2.1.4. `ChannelFollowerEntity` maps `(channel_id, user_id)` through `ChannelFollowerId`, a lazy channel relationship and an automatically generated `followed_at` timestamp. The user UUID is external to Channel Service and has no cross-service foreign key. The composite primary key supports channel lookups; a separate `user_id` index supports user lookups. Follower records must be removed explicitly before physically deleting a channel. Follow/unfollow operations update the stored counter atomically in the same transaction as the follower record.

`deleted_at` stores the channel's soft-delete timestamp. `ChannelEntity` uses `@SQLRestriction("deleted_at IS NULL")`, so Hibernate-generated channel queries automatically exclude soft-deleted records, including ID/slug/streamer lookups, existence checks, lists/counts and the query that locks a channel for update. Non-deleted banned channels remain visible and business services still check their status. Native SQL bypasses this restriction; the creation ownership check intentionally includes deleted records because `streamer_id` remains unique. The annotation filters reads; it does not convert repository deletion calls into soft deletes. Updating a user's profile will not automatically change channel profile fields.

## List channels

The authenticated channel lists use two independent endpoints through Gateway at `http://localhost:8888`:

- `GET /channel/api/channels`: channels the current user has not followed.
- `GET /channel/api/channels/following`: channels the current user has followed.

Both require an access-token cookie and a UUID JWT subject. They return active, non-deleted channels and exclude channels owned by the current user, including legacy self-follows. Legacy channels with an unknown owner remain eligible. Both conditions apply before pagination and to pagination totals.

Both endpoints use Spring binding and Jakarta Bean Validation on their request DTOs without a custom raw-query validator. Parameters outside each request model are ignored. The old `following` parameter does not select a list anymore: use the corresponding endpoint. The unfollowed endpoint supports repeated `categoryIds` values; scalar conversion follows standard Spring binding.

### Followed channels

`GET /channel/api/channels/following` accepts exactly four query parameters:

| Parameter | Default | Rules |
| --- | --- | --- |
| `sortBy` | `FOLLOWER_COUNT` | Exactly one criterion: `FOLLOWER_COUNT` or `FOLLOWED_AT`. |
| `sortDirection` | `DESC` | `ASC` or `DESC`, applied only to the selected criterion. |
| `page` | `0` | Zero-based, at least zero. |
| `size` | `20` | From 1 through 100. |

Without sort parameters, channels are ordered by stored follower count descending. `FOLLOWED_AT` sorts by the authenticated user's current `channel_followers.followed_at`: descending means most recently followed first. Selecting this criterion without a direction defaults to descending. Unfollowing and following again creates a new follow timestamp. The endpoint does not accept `keyword` or `categoryIds` and never reads interests or calls User Service.

Creation time descending and channel ID descending break ties. Follower sorting never uses follow time as a tie-breaker, and follow-time sorting never uses follower count as a tie-breaker. The enum parameter selects one criterion; comma-separated multiple criteria and invalid bound values return HTTP 400. No custom validation of repeated raw query parameters is applied.

### Unfollowed channels

`GET /channel/api/channels` accepts these query parameters:

| Parameter | Default | Rules |
| --- | --- | --- |
| `keyword` | No text filter | Literal substring in name, description or slug, ignoring case and preserving accents; maximum 255 raw characters. Surrounding whitespace is stripped; null/blank text disables filtering. LIKE wildcards and escape characters are literal. |
| `categoryIds` | No explicit category filter | Optional nonempty UUID set; comma-separated IDs or repeated parameters accepted. IDs are deduplicated; membership in any selected active category matches. Missing/inactive IDs contribute no matches. |
| `sortDirection` | `DESC` | Only `DESC` is supported. `ASC`, empty and invalid values return 400. The parameter remains available for future business changes. |
| `page` | `0` | Zero-based, at least zero. |
| `size` | `20` | From 1 through 100. |

| Inputs | Selection and order |
| --- | --- |
| Keyword and category IDs | Keyword AND any selected active category, follower count descending. |
| Keyword only | Keyword matches, follower count descending; interests are not consulted. |
| Category IDs only | Any selected active category, follower count descending; interests are not consulted. |
| Neither filter | All eligible unfollowed channels; channels matching at least one active interest category first, then all remaining channels. Each group uses follower count descending. |

Explicit filters have no interest-based ranking or fallback to unrelated channels. No filter matches means an empty page. Without explicit filters, preferences are read once from `GET /user/internal/api/users/{userId}/category-preferences` before the local read transaction. Empty preferences, no matching channels or only inactive/missing interest categories retain all eligible channels in follower order. Matching more than one interest gives no extra priority. Creation time descending and channel ID descending break follower-count ties in every group.

The dedicated Feign client `userCategoryPreferences` uses a 2-second connect timeout and 5-second read timeout in the development configuration. Connection failures, timeouts or upstream 5xx responses log a warning and fall back to follower order for the unfiltered unfollowed list only. A missing user returns 404 / code `1022`; unexpected upstream 4xx or malformed success envelopes/lists return 503 / code `1008`. Null lists are invalid; an empty list is valid. No preference cache is used, so subsequent requests read current preferences. Requests with filters and all followed-list requests do not call this service.

### Pagination, response and query behavior

The offset `page * size` must fit a signed 32-bit integer. Explicitly empty required parameters, malformed bound values, unsupported sort values and DTO validation failures return HTTP 400 / code `1004`. Missing authentication or invalid user subjects return 401 / code `1001`. Channel database failures return 503 / code `1003`. Out-of-range pages return HTTP 200 with empty content and the correct totals.

The response remains `ApiResponse<PageResponse<ChannelListResponse>>`. Each item has exactly four fields: `id`, `name`, `avatarUrl` and the 64-bit `numberOfFollower`. A null avatar URL is preserved. `bannerUrl` is omitted from both list endpoints; the channel detail/update response continues to expose it. No timestamp or ranking field is added.

Specifications compose eligibility, ownership, literal keyword, active-category membership and follow membership. Category and follow checks use EXISTS/NOT EXISTS to avoid duplicate rows. `ChannelListRepositoryImpl` applies follow-time ordering or a CASE/EXISTS interest priority in the database, followed by pagination. The count query uses the same selection predicates without ordering or interest exclusion. Follower counts use the stored counter; no categories are loaded per item. Concurrent follower, membership or preference changes can shift offset page boundaries.

```shell
curl -b cookies.txt 'http://localhost:8888/channel/api/channels/following'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels/following?sortBy=FOLLOWED_AT&sortDirection=ASC'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels/following?sortBy=FOLLOWER_COUNT&sortDirection=DESC'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?page=0&size=20'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?keyword=gaming&sortDirection=DESC'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?categoryIds=550e8400-e29b-41d4-a716-446655440000&keyword=gaming'
```

`ChannelFollowingIntegrationTest` covers both following sort criteria/directions, defaults, user isolation, ownership, authentication, membership changes, validation and pagination. `ChannelListIntegrationTest` covers the four discovery cases, literal text, category membership, interest ranking across page boundaries, changing preferences, empty/unmatched/inactive interests and correct totals. `UserCategoryPreferenceClientIntegrationTest` runs the real Feign transport against a local HTTP stub, including timeout configuration, fallback, invalid envelopes and the complete HTTP-to-database discovery workflow. The list suites accept the disposable PostgreSQL test properties below.

## Follow and unfollow a channel

Each authenticated request targets exactly one channel UUID in the path, with no request body:

| Action | Endpoint | Conditions |
| --- | --- | --- |
| Follow | `PUT /channel/api/channels/{channelId}/follow` | Channel exists, is not soft-deleted, is `ACTIVE`, and the user does not already follow it. |
| Unfollow | `DELETE /channel/api/channels/{channelId}/follow` | Channel exists, is not soft-deleted, and the user currently follows it. Any channel status, including `BANNED`, is allowed. |

User identity comes exclusively from `AuthenticationUserId` using the authenticated JWT subject. No follow-specific authority or streamer role is required. Use the existing authenticated cookie through Gateway:

```shell
curl -X PUT -b cookies.txt 'http://localhost:8888/channel/api/channels/550e8400-e29b-41d4-a716-446655440000/follow'
curl -X DELETE -b cookies.txt 'http://localhost:8888/channel/api/channels/550e8400-e29b-41d4-a716-446655440000/follow'
```

Both operations return HTTP 200 with `status: 200`, `code: 1000`, `result: true`, a timestamp and message `Channel followed successfully` or `Channel unfollowed successfully`. Repeated operations are rejected:

| Condition | HTTP status | Error code |
| --- | --- | --- |
| Missing or invalid authentication | 401 | `1001` |
| Malformed channel UUID, including a list of UUIDs | 400 | `1004` |
| Missing or soft-deleted channel | 404 | `1009` |
| Follow on an inactive channel | 403 | `1010` |
| User already follows the channel | 409 | `1019` |
| User does not follow the channel | 409 | `1020` |
| Database access failure | 503 | `1003` |

`ChannelFollowService` checks channel existence and, for follow, active status using a normal read. `ChannelFollowerRepository` uses `INSERT ... ON CONFLICT DO NOTHING` to atomically check membership and create a follow; zero inserted rows means an existing follow. The database supplies `followed_at`, and duplicate requests preserve the original timestamp. Unfollow uses a conditional delete scoped to both IDs; zero deleted rows means no current follow. Concurrent duplicate requests therefore produce one successful change and business conflicts for the remaining requests, without a separate membership precheck. Database constraints still enforce referential integrity.

Each operation runs in a local transaction. After inserting or deleting one follower record, `ChannelRepository` executes an atomic `UPDATE channels SET follower_count = follower_count + 1` or `follower_count - 1`, scoped to the non-deleted channel. A duplicate follow or missing unfollow does not update the counter. If the counter update fails or affects no row, the follower change rolls back; a decrement from zero is rejected with HTTP 503 / code `1003`. Counter updates flush pending JPA changes and clear the persistence context to avoid retaining stale counter values. They preserve profile fields and audit timestamps, and call no remote service. The initial channel read takes no explicit row lock; the counter update acquires the database's normal update lock until transaction completion. Channel status is validated at read time.

Flyway migration `V4__backfill_channel_follower_counts.sql` sets every existing channel's counter to its current follower-record count, including zero-follower, banned and soft-deleted channels. Before upgrading an existing deployment, stop follow/unfollow traffic and all older Channel Service instances, then apply the migration and start the new version before resuming writes. This prevents older instances from changing follower records without updating counters during the transition. No scheduled recount job is installed.

`ChannelFollowIntegrationTest` covers authentication, validation, status rules, user/channel isolation, duplicate requests, concurrent increments/decrements, mixed follow/unfollow requests, transaction rollback, counter-update failures, persistence-context refresh, preserved profile data and immediate following-list totals. It accepts the disposable PostgreSQL properties below and creates and drops an isolated test schema.

## Create channel

`POST /channel/internal/api/channels` creates a channel for a supplied streamer UUID and its owner user UUID. User Service calls this endpoint during become-streamer registration, supplying `streamerId`, `userId`, display name and avatar URL from its own records. The endpoint currently accepts calls without a JWT or an internal token; caller authentication is deferred. Gateway's `/channel/api/**` route does not route this internal endpoint.

```shell
curl -X POST http://localhost:8081/channel/internal/api/channels \
  -H 'Content-Type: application/json' \
  -d '{"streamerId":"550e8400-e29b-41d4-a716-446655440000","userId":"550e8400-e29b-41d4-a716-446655440001","name":"My channel","avatarUrl":"https://example.com/avatar.png"}'
```

| Field | Rules |
| --- | --- |
| `streamerId` | Required UUID. Channel Service does not check whether the streamer exists in User Service. |
| `userId` | Required UUID of the user owning the streamer/channel. User Service supplies it from the authenticated become-streamer workflow. Channel Service persists it without calling User Service to verify the mapping. |
| `name` | Required, nonblank; leading/trailing whitespace is removed before enforcing the 150-character maximum. |
| `avatarUrl` | Optional or `null`; when supplied, a nonempty HTTP/HTTPS URL with at most 500 characters. |

Creation returns HTTP 200 with the standard envelope: `status: 200`, `code: 1000`, `message: "Channel created successfully"`, `timestamp`, and `result: true`. The result is a boolean acknowledgement; channel details are not returned. A streamer with an existing channel, including a soft-deleted channel, receives HTTP 400 / code `1005` (`Channel already existed`).

New channels persist `userId` in `channels.user_id`, use status `ACTIVE`, slug `channel-{streamerId}`, and null description, banner URL and deletion timestamp. Repeated calls leave the existing channel's owner user ID, name, avatar, audit timestamps, banned status and soft-delete state unchanged. A banned or deleted channel is not restored by this endpoint.

Flyway migration `V3__add_channel_user_id.sql` adds `user_id` as a nullable UUID so existing channel records remain valid. Existing rows retain `NULL` until their owner mapping is backfilled from User Service; the migration does not infer user IDs from streamer IDs. The create API and service require a non-null owner for new channels. The column is an external user reference with no cross-service foreign key and is not changed by JPA updates. Channel list/update response contracts remain unchanged. Preventing users from following their own channel will be implemented separately.

The unique `streamer_id` constraint prevents duplicate channels. The creation precheck uses native `existsByStreamerIdIncludingDeleted` to preserve reserved streamer IDs even when the existing channel is hidden by the entity restriction. Insertion joins the local transaction; integrity conflicts and other storage failures return HTTP 503 / code `1003` (`Channel data unavailable`). Invalid fields, malformed JSON, missing bodies and invalid UUIDs return HTTP 400 / code `1004` (`Invalid channel request`). These error responses contain `result: false`. Responses do not expose database details.

The channel transaction commits independently of a caller transaction. A future User Service rollback may therefore leave an orphan channel; cleanup is a separate future job.

The implementation stays within `channel.controller.internal`, `channel.dto.request`, `channel.dto.validation`, and `channel.service.impl`, with the contract in `channel.service`. Shared response, validation error handling and security configuration remain in `common`.

## Update channel

`PATCH /channel/api/channels/me` updates the authenticated user's channel description, banner and assigned categories. Call it through API Gateway at port `8888`; Gateway verifies the access-token cookie before forwarding it. The controller requires the exact `CHANNEL_UPDATE` authority using `@PreAuthorize`. A role alone does not grant access. Assign this permission to the appropriate role in User Service and refresh/login to issue an access token containing it.

The endpoint consumes `multipart/form-data`, bound through `@Valid @ModelAttribute UpdateChannelRequest`:

| Field | Rules |
| --- | --- |
| `description` | Optional; when supplied, must contain non-whitespace text and be at most 1,000 characters. |
| `bannerUrl` | Optional file part; when supplied, must be nonempty JPEG, PNG or WebP, at most 5 MiB and 16 million pixels. |
| `categoryIds` | Optional; when supplied, must contain at least one valid UUID and no null/empty elements. Send repeated fields or indexed fields such as `categoryIds[0]`. Duplicate IDs are collapsed. |

```shell
curl -X PATCH http://localhost:8888/channel/api/channels/me \
  -b cookies.txt \
  -F 'description=Livestream games and chat' \
  -F 'bannerUrl=@/path/to/banner.png' \
  -F 'categoryIds=550e8400-e29b-41d4-a716-446655440000'
```

Omit a field to preserve its current value: omitted fields bind to null. Empty descriptions, empty banner files and empty category lists are rejected, so empty values cannot clear existing data. Sending `null` as text is not equivalent to omitting a field. Sending a banner URL as a text field is rejected. JSON requests are not supported. The response's `bannerUrl` remains a string URL. Omitting all fields leaves the channel unchanged after the usual permission and ownership checks.

The file format is detected from image content; supplied filenames and MIME types do not select its format or storage key. Banners are uploaded to R2 under `channels/<channelId>/banners/<uuid>.<extension>`, and the database stores only the full public URL (at most 500 characters). When a banner is supplied, a successful transaction replaces that URL and then attempts to delete the previous banner managed for that channel. A rolled-back transaction attempts to delete the new upload while preserving the previous banner. An omitted banner triggers no storage operations. Cleanup is best effort; R2 and database operations do not share a distributed transaction. URLs belonging to other channels or external providers are not deleted.

The submitted category list replaces the previous selection, keeping existing links and their creation timestamps when selected again. An omitted category list preserves all current links without revalidating their category status, and the response still includes their IDs.

The service obtains the user UUID from the JWT subject, then uses Feign to call `GET /user/internal/api/streamers/by-user/{userId}/active`. A successful response must contain `result.streamerId`. User Service checks the current streamer record and its `ACTIVE` status; user IDs and streamer IDs are distinct. Client-supplied channel/streamer IDs cannot select a different channel.

The resolved streamer must own an existing, active, non-deleted channel. Every requested category must exist and be `ACTIVE`. Missing channels are not created automatically. The service locks the channel row and commits profile/category changes together in one local transaction, so concurrent updates do not mix category selections. Stream-session categories are managed separately.

HTTP 200 returns `ApiResponse<ChannelResponse>` with `id`, `streamerId`, `name`, `slug`, `description`, `avatarUrl`, `bannerUrl`, `status` and distinct `categoryIds` under `result`. Name, slug and avatar remain the current values.

| Failure | HTTP | Code |
| --- | --- | --- |
| Missing/invalid authentication or user UUID in the principal | 401 | 1001 |
| Missing `CHANNEL_UPDATE` authority | 403 | 1002 |
| Invalid form fields or malformed multipart request | 400 | 1004 |
| Unsupported request Content-Type; update channel requires multipart/form-data | 415 | 1017 |
| No streamer for the user | 404 | 1006 |
| Streamer is not active | 403 | 1007 |
| User Service timeout, error or invalid response | 503 | 1008 |
| Channel missing or soft-deleted | 404 | 1009 |
| Channel is not active | 403 | 1010 |
| Requested category missing | 400 | 1011 |
| Requested category inactive | 400 | 1012 |
| Local persistence failure | 503 | 1003 |
| Invalid banner image | 400 | 1013 |
| File exceeds 5 MiB or multipart request exceeds 6 MiB | 413 | 1014 |
| R2 upload failed | 502 | 1015 |
| Generated banner URL exceeds the database limit | 503 | 1016 |

`USER_SERVICE_BASE_URL` defaults to `http://localhost:8080/user` in the development profile. Feign uses a 2-second connection timeout and 5-second read timeout. Internal lookup failure stops the update without changing channel data.

R2 configuration and storage follow the same layout and class names as User Service: `common.configuration.R2Config`, `common.service.R2StorageService`, and `common.service.impl.R2StorageServiceImpl`. Banner validation and transaction coordination remain in the channel module.

`ChannelUpdateIntegrationTest` runs the real security, multipart binding, validation, service, database and Feign stack with a local User Service stub and mocked R2 storage service. It includes real HTTP multipart PATCH requests and file-size rejection, plus permission/ownership checks, partial updates, omitted fields, empty-value rejection, supported image formats, spoofed metadata, upstream/storage failures, transaction cleanup and concurrent updates. `R2StorageServiceImplTest` verifies S3 upload metadata/content and limits cleanup to owned banner keys using a mocked SDK client; tests do not upload to a real R2 bucket.

## Development

The service listens at `http://localhost:8081/channel`, matching Gateway's `/channel/api/**` route. The repository's Docker Compose exposes channel PostgreSQL at `localhost:5433`, database `reallive_channel`, username `channel_service`.

Configure `BUCKET_NAME`, `PUBLIC_BASE_URL`, `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY` as in User Service. Use the S3-compatible endpoint without the bucket suffix and a public bucket/custom-domain URL for `PUBLIC_BASE_URL`. Only the backend uploads to R2.

Copy `.env.example` to `.env` and configure the database and R2 credentials. Optional `CHANNEL_DB_URL` and `CHANNEL_DB_USERNAME` override the development defaults. Set `REDIS_PASSWORD` to the same value as the repository-root `.env` used by Docker Compose. Redis authorities must use the same host, port and logical database as User Service and Gateway. The development defaults are `localhost:6379`, database `0`.

Export those variables into the shell or configure the IDE run configuration to load this service's `.env`; Spring Boot does not load `.env` files automatically. After changing credentials, restart the service. A missing Redis password against the Compose Redis instance produces `NOAUTH HELLO`.

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

## Validation

```shell
./mvnw clean verify
```

Tests use H2 in PostgreSQL compatibility mode, apply the actual Flyway migration and validate the JPA mappings. They cover CRUD/lookups, composite keys, timestamps, required columns, status constraints, uniqueness and foreign keys. Channel creation tests exercise the real HTTP/security/service/persistence stack, including request validation, retries, banned/deleted records, caller rollback, database failure rollback and simultaneous inserts. Persistence and creation tests can also run against a disposable PostgreSQL database:

```shell
./mvnw \
  -Dchannel.test.database-url=jdbc:postgresql://localhost:5433/channel_test \
  -Dchannel.test.database-username=channel_test clean verify
```

Supply `channel.test.database-password` if required. Use a dedicated test database and a user with schema creation permission. Persistence and channel creation tests create and drop their own randomly named schemas. `ChannelListIntegrationTest` also accepts these database properties; it applies migrations in the supplied test database's default schema and rolls back test rows after each test. The channel-service workflow runs the full suite with PostgreSQL 17.

Common tests cover response serialization, application error propagation, HTTP error envelopes, JWT claim parsing, cookie selection and scope-to-authority mapping. MockMvc tests run the real security filter chain and method authorization with the development context path and a custom access-cookie name, including 401/403 responses and authenticated database failures returning 503. `BaseEntity` audit timestamps are exercised by the persistence tests.
