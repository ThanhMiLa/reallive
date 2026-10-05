# User Service

## Authentication scaffold

The first business module is `auth`, which will own registration, login,
refresh and logout. These operations belong to one module rather than separate
Clean Architecture modules for each endpoint.

This scaffold contains package placeholders only. It does not implement
endpoints, use cases, token handling, persistence or security configuration.
Remove each `.gitkeep` when its directory contains implementation files.

## Main source structure

The package tree below is rooted at `src/main/java/`.

```text
com/reallive/userservice/
├── UserServiceApplication.java
├── auth/
│   ├── domain/
│   │   ├── model/
│   │   └── exception/
│   ├── application/
│   │   ├── port/
│   │   │   ├── in/
│   │   │   └── out/
│   │   ├── service/
│   │   └── dto/
│   ├── infrastructure/
│   │   ├── persistence/
│   │   │   ├── entity/
│   │   │   ├── repository/
│   │   │   ├── adapter/
│   │   │   └── mapper/
│   │   └── security/
│   └── presentation/
│       └── rest/
│           ├── controller/
│           ├── request/
│           └── response/
└── common/
    ├── infrastructure/
    │   └── security/
    └── presentation/
        └── exception/
```

## Responsibilities

| Package | Responsibility | Examples to add during implementation |
| --- | --- | --- |
| `auth/domain/model` | Framework-independent authentication models and business rules. | `User` |
| `auth/domain/exception` | Authentication business rule failures. | `InvalidCredentialsException` |
| `auth/application/port/in` | Contracts for invoking authentication use cases. | `RegisterUseCase`, `LoginUseCase`, `RefreshTokenUseCase`, `LogoutUseCase` |
| `auth/application/port/out` | Contracts for persistence, password handling and token lifecycle operations. | `UserRepositoryPort`, `PasswordVerifierPort`, `TokenProviderPort` |
| `auth/application/service` | Use-case orchestration through domain models and output ports. | `RegisterService`, `LoginService`, `RefreshTokenService`, `LogoutService` |
| `auth/application/dto` | Use-case input and result types, independent of HTTP. | `LoginCommand`, `LoginResult` |
| `auth/infrastructure/persistence/entity` | JPA persistence models. | `UserJpaEntity` |
| `auth/infrastructure/persistence/repository` | Spring Data repository interfaces. | `SpringDataUserRepository` |
| `auth/infrastructure/persistence/adapter` | Implementations of application persistence ports. | `UserRepositoryAdapter` |
| `auth/infrastructure/persistence/mapper` | Conversion between domain models and persistence entities. | `UserPersistenceMapper` |
| `auth/infrastructure/security` | Authentication-specific password and token adapters. | `BCryptPasswordVerifierAdapter`, `JwtTokenProviderAdapter` |
| `auth/presentation/rest/controller` | Incoming authentication HTTP endpoints invoking input ports. | `AuthController` |
| `auth/presentation/rest/request` | HTTP request types and input validation. | `LoginRequest` |
| `auth/presentation/rest/response` | HTTP response types. | `LoginResponse` |
| `common/infrastructure/security` | Service-wide Spring Security configuration. | `SecurityConfig` |
| `common/presentation/exception` | Service-wide HTTP error mapping and error response types. | `GlobalExceptionHandler`, `ErrorResponse` |

The class names above are examples, not implemented classes or finalized
contracts. Define their fields and methods when implementing the corresponding
use case.

## Dependency rules

- Domain models do not depend on Spring, JPA, HTTP or token libraries.
- Application services use domain models and output ports rather than technical
  clients or Spring Data repositories.
- Infrastructure implements output ports and owns framework-specific details.
- REST controllers invoke input ports and map HTTP requests and responses.
- Keep authentication-specific code inside `auth`; reserve `common` for
  service-wide technical concerns.
- Other microservices must access user data through service contracts, not this
  service's database.

## Test structure

Under `src/test/java/com/reallive/userservice/`, placeholders are provided for:

- `auth/application/service`: use-case behavior with controlled port dependencies.
- `auth/infrastructure/security`: password and token adapter behavior.
- `auth/presentation/rest`: HTTP contracts and security integration.

Add tests alongside implemented behavior. No placeholder test classes are
included.

## Architecture reference

Follow the repository's
[Clean Architecture skill](../.agents/skills/clean-architecture/SKILL.md).
