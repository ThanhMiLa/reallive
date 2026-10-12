# Channel Service

Java 21 / Spring Boot service owning channel profiles, categories and their relationships.

## Persistence foundation

```text
com.reallive.channelservice/
├── ChannelServiceApplication.java
├── common/entity/BaseEntity.java
└── channel/
    ├── entity/ChannelEntity.java
    ├── entity/ChannelStatus.java
    ├── entity/CategoryEntity.java
    ├── entity/CategoryStatus.java
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

`deleted_at` stores the channel's soft-delete timestamp. Repository lookups return persisted records, including banned and soft-deleted channels; filtering belongs to the future business workflow. Updating a user's profile will not automatically change channel profile fields.

## Development

The service listens at `http://localhost:8081/channel`, matching Gateway's `/channel/api/**` route. The repository's Docker Compose exposes channel PostgreSQL at `localhost:5433`, database `reallive_channel`, username `channel_service`.

Export `CHANNEL_DB_PASSWORD` or set it in the IDE. Optional `CHANNEL_DB_URL` and `CHANNEL_DB_USERNAME` override the development defaults. Spring Boot does not load the root `.env` file automatically.

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

This step supplies persistence only. HTTP workflows and service-to-service authentication will be added with the channel creation API.

## Validation

```shell
./mvnw clean verify
```

Tests use H2 in PostgreSQL compatibility mode, apply the actual Flyway migration and validate the JPA mappings. They cover CRUD/lookups, composite keys, timestamps, required columns, status constraints, uniqueness and foreign keys. Repository tests can also run against a disposable PostgreSQL database:

```shell
./mvnw -Dtest=ChannelPersistenceTest \
  -Dchannel.test.database-url=jdbc:postgresql://localhost:5433/channel_test \
  -Dchannel.test.database-username=channel_test test
```

Supply `channel.test.database-password` if required. Use a dedicated test database and a user with schema creation permission. The repository test creates and drops only its own randomly named schema.
