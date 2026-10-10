---
name: code-architecture
description: Apply this repository's common and feature-module package conventions in Java microservices when placing new code, adding integrations, refactoring packages, or reviewing code architecture. Distinguish infrastructure adapters from clients calling other application services.
---

# Service Code Architecture

Use a consistent package structure inside each Java backend service: a service-local `common` package alongside business modules such as `auth`, `user`, and `streamer`. Organize by business module first, then by responsibility inside that module. Do not apply this Java package layout to frontend, Android, or NestJS code.

## Service and Module Ownership

- Read the relevant service responsibilities in [System Architecture](../../../docs/architecture/System%20Architecture.md) and feature/data-model documentation before choosing an owner for new code.
- Keep each microservice independently built and responsible for its own business capabilities and data. Do not import another microservice's Java entities, repositories, or implementation classes; communicate through APIs or events.
- A business module is a package inside a service, not a separate microservice or build module. For example, User Service contains `auth`, `user`, and `streamer` packages under `com.reallive.userservice`.
- Place feature-specific code in its owning module. Do not create service-root `controller`, `service`, or `repository` packages that mix all features.
- Modules in the same service may collaborate. Preserve existing ownership and dependencies unless the requested work requires changing them; do not move `auth.entity.UserEntity` merely because its name includes `User`.

## Package Layout

Use this layout as a placement guide. Create only packages needed by the feature; do not generate empty folders or placeholder classes.

```text
com.reallive.<service>/
├── <Service>Application.java
├── common/
│   ├── configuration/
│   ├── security/
│   ├── util/
│   ├── entity/
│   │   └── BaseEntity.java
│   ├── exception/
│   │   ├── AppException.java
│   │   ├── ErrorCode.java
│   │   └── GlobalExceptionHandler.java
│   └── dto/
│       └── ApiResponse.java
├── auth/
├── user/
│   ├── adapter/
│   │   ├── kafka/
│   │   │   └── message/
│   │   └── redis/
│   ├── client/
│   ├── controller/
│   ├── repository/
│   ├── service/
│   │   └── impl/
│   ├── util/
│   ├── entity/
│   ├── mapper/
│   └── dto/
│       ├── request/
│       └── response/
└── streamer/
```

`auth` and `streamer` follow the same responsibility-based layout as `user`. Use the exact package names `configuration`, `repository`, and `util`.

## Common Code

`common` is shared by modules within one service. Apply the same convention across services without introducing a monorepo-wide shared Java module.

- `common.configuration`: shared Spring bean and infrastructure configuration, such as Redis connections, password encoding, or object-storage configuration. Feature-specific integration operations belong in the owning module's adapter or client.
- `common.security`: security configuration and shared authentication/authorization infrastructure.
- `common.util`: helpers genuinely reused across modules, such as `AuthenticationUserId` and `UserValidator` in User Service. A helper used only for one feature belongs in that module's `util`; do not move business workflows into utilities.
- `common.entity.BaseEntity`: shared persistence fields. User Service's reference is a JPA `@MappedSuperclass` with `createdAt` and `updatedAt`; identifiers remain in concrete entities. Reuse the local base entity for entities needing these fields instead of duplicating them. Do not force inheritance onto entities with a different persistence model.
- `common.exception`: the service-wide application error model and HTTP error handler. Use `AppException(ErrorCode)` and follow [exception-handling](../exception-handling/SKILL.md) when adding or changing errors. Feature modules use these definitions rather than creating their own exception hierarchy.
- `common.dto`: response envelopes or other contracts shared across the service, such as `ApiResponse`; feature request/response DTOs stay in the feature module.
- Shared services and integrations can also live under `common` when actually reused across modules. User Service already has `common.service.R2StorageService` and `common.service.impl.R2StorageServiceImpl`; preserve their location during unrelated changes. For new shared integrations, use `common.adapter` or `common.client` according to the rules below.

Do not place a feature in `common` merely because another module calls it; keep the feature under its business owner.

## Module Responsibilities

| Package | Responsibility |
| --- | --- |
| `controller` | HTTP endpoints, request validation, and response construction; delegate business workflows to services. |
| `service` | Business contracts and use cases; place implementations in `service.impl` as in the existing services. |
| `repository` | Persistence access for the module's entities, such as Spring Data JPA repositories. |
| `entity` | The module's persistence entities and related domain enums. |
| `dto.request`, `dto.response` | Feature request/response contracts; follow the existing module's naming. |
| `mapper` | Transformations between entities and DTOs; keep business workflows and external I/O out of mappers. |
| `util` | Feature-specific helpers, such as avatar validation or OTP hashing. |
| `adapter` | Integration with infrastructure or externally supplied providers. |
| `client` | API calls to other application microservices. |

Services coordinate repositories, adapters, and clients. Keep SDK, broker, cache, and remote HTTP details inside the appropriate integration package rather than embedding them in controllers or business workflows. Keep business decisions in services and transport/persistence details in their respective components.

## Adapter versus Client

Choose the package by the responsibility of the target system, not by the library or protocol used to connect to it.

- **Adapter:** integrates with supplied infrastructure or external providers, such as Kafka, Redis, RabbitMQ, object storage, or a Media Server such as MediaMTX/SRS. Group by technology/provider when useful: `adapter.kafka`, `adapter.redis`, `adapter.rabbitmq`, or `adapter.media`. An HTTP integration with a Media Server still belongs in an adapter even if it uses `WebClient`.
- **Client:** calls another application microservice's API, such as Channel Service, User Service, or Notification Service. Put Feign clients (`@FeignClient`), WebClient wrappers, and related remote API DTOs under the owning module's `client` package, with target-specific subpackages when useful. Feign and WebClient are implementation choices, not mandatory dependencies.
- Broker communication remains in `adapter.kafka` or `adapter.rabbitmq` even when the event is consumed by another application service. For example, requesting a verification email through Kafka uses an adapter; requesting it through Notification Service's HTTP API uses a client.
- Keep integration contracts and implementations together as appropriate. User Service uses `user.adapter.kafka.EmailVerificationEmailPublisher` with `KafkaEmailVerificationEmailPublisher`, and `user.adapter.redis.EmailVerificationOtpStore` with `RedisEmailVerificationOtpStore`. Reuse this pattern where it helps isolate the integration; do not introduce an interface solely to fill the package tree.
- Keep broker message types near the adapter, following `user.adapter.kafka.message`. Keep remote API request/response types near their client rather than importing entities from the destination service.
- Place an adapter or client in the feature that owns its use case. Promote it to `common.adapter` or `common.client` only when multiple modules genuinely share the integration.

## Applying the Convention

Inspect the target service's packages before editing. User Service is the reference implementation, including [BaseEntity](../../../user-service/src/main/java/com/reallive/userservice/common/entity/BaseEntity.java) and [EmailVerificationServiceImpl](../../../user-service/src/main/java/com/reallive/userservice/user/service/impl/EmailVerificationServiceImpl.java).

Follow [code-style](../code-style/SKILL.md) for Java implementation and [service-javadoc](../service-javadoc/SKILL.md) when creating or editing service interfaces. When moving code, update package declarations, imports, Spring/JPA discovery configuration where applicable, and tests; mirror the production packages under `src/test/java`.

Apply the convention to the requested work without turning a focused change into a service-wide package migration. Validate affected behavior with the module's relevant tests and build checks when implementation changes are made.
