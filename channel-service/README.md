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

Flyway migration `V2__add_follower_count_and_channel_followers.sql` adds `channels.follower_count` as a nonnegative `BIGINT NOT NULL DEFAULT 0`, initializing existing channels to zero, and creates the follower table from Data Model section 2.1.4. `ChannelFollowerEntity` maps `(channel_id, user_id)` through `ChannelFollowerId`, a lazy channel relationship and an automatically generated `followed_at` timestamp. The user UUID is external to Channel Service and has no cross-service foreign key. The composite primary key supports channel lookups; a separate `user_id` index supports user lookups. Follower records must be removed explicitly before physically deleting a channel. This persistence foundation does not automatically adjust the counter when follower rows change; follow/unfollow operations and a reconciliation job are separate work.

`deleted_at` stores the channel's soft-delete timestamp. `ChannelEntity` uses `@SQLRestriction("deleted_at IS NULL")`, so Hibernate-generated channel queries automatically exclude soft-deleted records, including ID/slug/streamer lookups, existence checks, lists/counts and the query that locks a channel for update. Non-deleted banned channels remain visible and business services still check their status. Native SQL bypasses this restriction; the creation ownership check intentionally includes deleted records because `streamer_id` remains unique. The annotation filters reads; it does not convert repository deletion calls into soft deletes. Updating a user's profile will not automatically change channel profile fields.

## List channels

`GET /channel/api/channels?page=0&size=20` returns a page of active, non-deleted channels. Call through Gateway at `http://localhost:8888/channel/api/channels` with an authenticated access-token cookie. The required `following` flag defaults to `false`, so omitting it selects the current user's unfollowed channels and still requires login. The GET path remains `permitAll()` at the request-filter level; controller method security enforces `@PreAuthorize("isAuthenticated()")`. Gateway forwards the path while validating any supplied token cookies. Channel writes and child paths retain their authentication requirements.

| Query parameter | Default | Rules |
| --- | --- | --- |
| `categoryIds` | No category filter | Optional set of category UUIDs, validated with `@Size(min = 1)` and non-null elements. May be omitted, but a supplied set must not be empty. Accepts one UUID, comma-separated UUIDs or repeated parameters. Matches membership in any selected active category. Duplicate IDs are ignored; missing or inactive categories contribute no matches. |
| `keyword` | No keyword filter | Optional literal substring of `name`, `description` or `slug`, ignoring case and preserving accents. `@Size(max = 255)` validates the raw field, including whitespace. The search Specification strips surrounding whitespace; blank text disables text filtering. `%`, `_`, `!` and backslash are treated literally. |
| `following` | `false` | Non-null Boolean: `true` selects channels the authenticated user currently follows; `false` selects channels they do not follow. Omission retains the default; an explicitly empty value is rejected. |
| `sortDirection` | `DESC` | Follower-count direction: `ASC` or `DESC`. |
| `page` | `0` | Zero-based page number, at least zero. |
| `size` | `20` | Page size from `1` through `100`. |

All filters combine with AND; selected categories combine with OR, as do keyword matches on the three searchable fields. Without category or keyword filters, all active, non-deleted channels in the selected following group are eligible. User identity comes exclusively from the JWT subject, never from a client-supplied user ID. Users with no current follows receive an empty page for `following=true` and all otherwise eligible channels for `following=false`.

The offset (`page * size`) must fit a signed 32-bit integer. Invalid UUIDs, empty category sets or null category elements, malformed Booleans, unsupported sort directions, overlong keywords, invalid pagination and explicitly empty `following`, `page`, `size` or `sortDirection` parameters return HTTP 400 / code `1004`. A valid list request without authentication, or with a missing/invalid user UUID in the JWT subject, returns HTTP 401 / code `1001`. Database access failures return HTTP 503 / code `1003`. Empty or out-of-range pages return HTTP 200 with an empty `content` list and totals calculated using the same filters.

Channels are ordered by `follower_count DESC` by default, or `ASC` when requested, followed by `created_at DESC, id DESC` for ties. The channel model currently has no visibility field; all active, non-deleted channels are eligible for public discovery. Stream/VOD visibility is independent. Concurrent follower changes, additions or removals can shift offset-based page boundaries.

```shell
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?keyword=gaming&sortDirection=ASC&page=0&size=10'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?categoryIds=550e8400-e29b-41d4-a716-446655440000&keyword=gaming&sortDirection=DESC'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?categoryIds=550e8400-e29b-41d4-a716-446655440000,550e8400-e29b-41d4-a716-446655440001'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?categoryIds=550e8400-e29b-41d4-a716-446655440000&categoryIds=550e8400-e29b-41d4-a716-446655440001'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?following=true&page=0&size=10'
curl -b cookies.txt 'http://localhost:8888/channel/api/channels?following=false&page=0&size=20'
```

```json
{
  "status": 200,
  "code": 1000,
  "message": "Channels retrieved successfully",
  "result": {
    "content": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "My channel",
        "bannerUrl": "https://example.com/banner.png",
        "avatarUrl": "https://example.com/avatar.png",
        "numberOfFollower": 1200
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  },
  "timestamp": "2026-10-15T03:00:00Z"
}
```

Each item contains exactly these five fields, including the channel UUID in `id`. Image URLs preserve their stored values, including null. `ChannelSpecifications` in `channel.repository.specification` composes active-status, category-membership, keyword and following predicates. `ChannelRepository` implements `JpaSpecificationExecutor`; `findAll(specification, pageable)` applies the same Specification to the page and count queries and loads paginated `ChannelEntity` records with their basic mapped columns. The service uses `ChannelMapper` (MapStruct) to convert each entity into `ChannelListResponse`, mapping `followerCount` to the 64-bit response field `numberOfFollower`. Category filtering uses an EXISTS subquery so channels with multiple categories do not create duplicate results. Following filtering uses EXISTS / NOT EXISTS against `channel_followers` for the authenticated user's UUID. Follower totals still come from the stored counter, without counting follower rows. The query does not load category entities or call another service. Pagination totals count eligible channels. Counter updates and reconciliation remain the responsibility of future follow/unfollow workflows.

Authenticated frontend clients can request `following=true` and `following=false` in parallel for separate sections. Each section maintains its own page, size, totals and loading state. This endpoint no longer exposes an unfiltered anonymous mode.

`ChannelListIntegrationTest` exercises the HTTP/service/persistence stack with authenticated requests, Flyway migrations, category/keyword filters in isolation and combination, literal wildcard handling, follower sorting in both directions, default/custom pagination, tied timestamps, stored follower totals, null images and invalid inputs. Gateway tests cover routing and protection of other channel operations.

`ChannelFollowingIntegrationTest` runs the real cookie authentication and method-security stack, verifying both following states, user isolation, independent pagination, users with no follows, combined filters, current membership after follow/unfollow and invalid credentials or inputs. It also accepts the disposable PostgreSQL test properties documented below.

## Create channel

`POST /channel/internal/api/channels` creates a channel for a supplied streamer UUID. The endpoint currently accepts calls without a JWT or an internal token. Caller authentication and User Service integration are deferred. Gateway's `/channel/api/**` route does not route this internal endpoint.

```shell
curl -X POST http://localhost:8081/channel/internal/api/channels \
  -H 'Content-Type: application/json' \
  -d '{"streamerId":"550e8400-e29b-41d4-a716-446655440000","name":"My channel","avatarUrl":"https://example.com/avatar.png"}'
```

| Field | Rules |
| --- | --- |
| `streamerId` | Required UUID. Channel Service does not check whether the streamer exists in User Service. |
| `name` | Required, nonblank; leading/trailing whitespace is removed before enforcing the 150-character maximum. |
| `avatarUrl` | Optional or `null`; when supplied, a nonempty HTTP/HTTPS URL with at most 500 characters. |

Creation returns HTTP 200 with the standard envelope: `status: 200`, `code: 1000`, `message: "Channel created successfully"`, `timestamp`, and `result: true`. The result is a boolean acknowledgement; channel details are not returned. A streamer with an existing channel, including a soft-deleted channel, receives HTTP 400 / code `1005` (`Channel already existed`).

New channels use status `ACTIVE`, slug `channel-{streamerId}`, and null description, banner URL and deletion timestamp. Repeated calls leave the existing channel's name, avatar, audit timestamps, banned status and soft-delete state unchanged. A banned or deleted channel is not restored by this endpoint.

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

Export `CHANNEL_DB_PASSWORD` or set it in the IDE. Optional `CHANNEL_DB_URL` and `CHANNEL_DB_USERNAME` override the development defaults. Spring Boot does not load the root `.env` file automatically.

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
