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
    ├── repository/ChannelRepository.java
    ├── repository/CategoryRepository.java
    └── repository/ChannelCategoryRepository.java
```

Flyway migration `V1__create_channels_and_categories_tables.sql` implements Data Model sections 2.1.1–2.1.3. Hibernate validates the schema instead of creating it. Channels and categories share creation/update timestamps through the service-local `BaseEntity`. Status is required explicitly: channels use `ACTIVE`/`BANNED`, categories use `ACTIVE`/`INACTIVE`.

`channels.streamer_id` is a unique external UUID owned by User Service, without a cross-service foreign key or entity relationship. The channel slug and category name/slug are also unique. Unique constraints provide their own indexes; the composite primary key already indexes `channel_categories.channel_id`, and a separate index supports category lookups.

All channel and category persistence code belongs to the `channel` business module; shared audit fields belong to `common.entity`.

`ChannelCategoryEntity` maps the composite `(channel_id, category_id)` key with `@EmbeddedId` and `@MapsId`, lazy parent relationships and a creation timestamp only. `ChannelCategoryId` is an embeddable identifier value type used by JPA and repository ID operations, not a fourth entity or table. There are three entities for the three tables. The association does not inherit `BaseEntity`, since the data model has no `updated_at` column for this table. There is no cascading parent deletion: remove category links explicitly before physically deleting a channel or category.

`deleted_at` stores the channel's soft-delete timestamp. Repository lookups return persisted records, including banned and soft-deleted channels. Updating a user's profile will not automatically change channel profile fields.

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

Creation and repeated calls both return HTTP 200 with the standard envelope: `status: 200`, `code: 1000`, `message: "Channel created successfully"`, `timestamp`, and `result: true`. The result is a boolean acknowledgement; channel details are not returned.

New channels use status `ACTIVE`, slug `channel-{streamerId}`, and null description, banner URL and deletion timestamp. Repeated calls acknowledge success without changing the existing channel's name, avatar, audit timestamps, banned status or soft-delete state. A banned or deleted channel is not restored by this endpoint.

The unique `streamer_id` constraint prevents duplicate channels. Each insertion runs in its own transaction. On an integrity conflict, the failed transaction rolls back before a fresh transaction looks up a channel created concurrently for the same streamer. Other storage failures return HTTP 503 / code `1003` (`Channel data unavailable`). Invalid fields, malformed JSON, missing bodies and invalid UUIDs return HTTP 400 / code `1004` (`Invalid channel request`). Both error responses contain `result: false`. Responses do not expose database details.

The channel transaction commits independently of a caller transaction. A future User Service rollback may therefore leave an orphan channel; cleanup is a separate future job.

The implementation stays within `channel.controller.internal`, `channel.dto.request`, `channel.dto.validation`, and `channel.service.impl`, with the contract in `channel.service`. Shared response, validation error handling and security configuration remain in `common`.

## Development

The service listens at `http://localhost:8081/channel`, matching Gateway's `/channel/api/**` route. The repository's Docker Compose exposes channel PostgreSQL at `localhost:5433`, database `reallive_channel`, username `channel_service`.

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

Supply `channel.test.database-password` if required. Use a dedicated test database and a user with schema creation permission. Each database test class creates and drops its own randomly named schema. The channel-service workflow runs the full suite with PostgreSQL 17.

Common tests cover response serialization, application error propagation, HTTP error envelopes, JWT claim parsing, cookie selection and scope-to-authority mapping. MockMvc tests run the real security filter chain and method authorization with the development context path and a custom access-cookie name, including 401/403 responses and authenticated database failures returning 503. `BaseEntity` audit timestamps are exercised by the persistence tests.
