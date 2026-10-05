# RealLive Clean Architecture Skill

## 1. Purpose

This document defines the Clean Architecture conventions used in the RealLive backend.

It is intended for:

- Developers working on RealLive.
- Coding agents such as Codex, Antigravity, or other AI coding assistants.
- Code review and refactoring guidance.
- Maintaining consistent architecture across all RealLive microservices.

RealLive uses:

- Java 21
- Spring Boot
- Spring Security
- PostgreSQL
- Spring Data JPA
- Flyway
- Kafka
- Redis
- OpenFeign / WebClient where necessary
- S3 / MinIO for object storage
- MediaMTX / SRS for streaming infrastructure
- Microservice architecture

The main architectural style is:

```text
Microservice
    ↓
Business Feature / Module
    ↓
Clean Architecture
```

Each sufficiently complex business module may contain:

```text
domain/
application/
infrastructure/
presentation/
```

This project uses a pragmatic Clean Architecture approach.

Do not introduce abstractions only for architectural purity.

---

# 2. Core Architecture

The four main layers are:

```text
presentation
    ↓
application
    ↓
domain

infrastructure
    ↑
implements ports required by application/domain
```

The dependency rule is:

> Dependencies must point toward the business core.

The business core must not depend on technical implementation details.

Conceptually:

```text
External World
     │
     ▼
Presentation
     │
     ▼
Application
     │
     ▼
Domain
```

Technical implementations connect from the outside:

```text
Application
     ↓
Port / Interface
     ↑
Infrastructure Adapter
     ↓
Database / Kafka / Redis / External API
```

---

# 3. Package-by-Feature First

RealLive does NOT use a global structure such as:

```text
controller/
service/
repository/
entity/
dto/
mapper/
```

for the entire microservice.

Instead, code is grouped by business feature first.

Example:

```text
payment-service/
├── wallet/
├── donation/
├── refund/
├── withdrawal/
├── common/
└── PaymentServiceApplication.java
```

Each complex module can then use Clean Architecture:

```text
wallet/
├── domain/
├── application/
├── infrastructure/
└── presentation/
```

Example:

```text
payment-service/
│
├── wallet/
│   ├── domain/
│   ├── application/
│   ├── infrastructure/
│   └── presentation/
│
├── donation/
│   ├── domain/
│   ├── application/
│   ├── infrastructure/
│   └── presentation/
│
├── withdrawal/
│   ├── domain/
│   ├── application/
│   ├── infrastructure/
│   └── presentation/
│
└── common/
```

Do NOT create one Clean Architecture module for every endpoint.

Bad:

```text
auth/
├── login/
│   ├── domain/
│   ├── application/
│   ├── infrastructure/
│   └── presentation/
├── register/
│   ├── domain/
│   ├── application/
│   ├── infrastructure/
│   └── presentation/
```

Preferred:

```text
auth/
├── domain/
├── application/
├── infrastructure/
└── presentation/
```

with multiple use cases:

```text
LoginUseCase
RegisterUseCase
RefreshTokenUseCase
LogoutUseCase
```

inside the `auth` module.

---

# 4. Domain Layer

## Responsibility

The `domain` layer contains the core business model and business rules.

It answers:

> What does the business mean?

Examples:

- A wallet cannot have a negative balance.
- A user cannot spend more coins than their balance.
- A stream cannot transition from `ENDED` back to `LIVE`.
- A donation cannot be refunded twice.
- A withdrawal cannot exceed the available streamer balance.

Example structure:

```text
domain/
├── model/
│   ├── Wallet.java
│   └── WalletStatus.java
│
├── exception/
│   └── InsufficientBalanceException.java
│
└── service/
    └── WalletDomainService.java
```

Domain code should be plain Java whenever possible.

Example:

```java
public class Wallet {

    private long balance;

    public void spend(long amount) {
        if (amount <= 0) {
            throw new InvalidAmountException();
        }

        if (balance < amount) {
            throw new InsufficientBalanceException();
        }

        balance -= amount;
    }

    public void deposit(long amount) {
        if (amount <= 0) {
            throw new InvalidAmountException();
        }

        balance += amount;
    }
}
```

## Domain MUST NOT depend on

Do not use the following inside domain models:

```java
@Entity
@Table
@Column
@Repository
@Service
@Component
@RestController
KafkaTemplate
RedisTemplate
WebClient
FeignClient
JpaRepository
```

Domain must not know about:

- PostgreSQL
- MongoDB
- Kafka
- RabbitMQ
- Redis
- HTTP
- OpenFeign
- MediaMTX
- payOS
- VNPay
- MoMo
- S3
- Spring MVC
- Spring Data JPA

The domain may use Java standard library types such as:

```java
UUID
Instant
LocalDateTime
BigDecimal
Optional
List
Map
```

where appropriate.

---

# 5. Application Layer

## Responsibility

The `application` layer contains application use cases.

It answers:

> What steps are required to complete a business operation?

Example:

```text
Donate Gift
```

may require:

```text
Load donor wallet
→ Load gift
→ Calculate price
→ Apply Wallet.spend()
→ Create Donation
→ Update streamer revenue
→ Save changes
→ Publish event
```

The application layer coordinates these operations.

Example structure:

```text
application/
├── port/
│   ├── in/
│   └── out/
│
├── service/
├── dto/
├── command/
├── query/
└── exception/
```

Not every module needs every package.

---

# 6. Input Ports / Use Cases

Application capabilities exposed to the outside are represented as input ports.

Example:

```text
application/
└── port/
    └── in/
        └── LoginUseCase.java
```

Example:

```java
public interface LoginUseCase {

    LoginResult execute(LoginCommand command);
}
```

The implementation belongs to application:

```text
application/
└── service/
    └── LoginService.java
```

Example:

```java
@Service
@RequiredArgsConstructor
public class LoginService implements LoginUseCase {

    private final UserRepositoryPort userRepository;
    private final PasswordVerifierPort passwordVerifier;
    private final TokenProviderPort tokenProvider;

    @Override
    public LoginResult execute(LoginCommand command) {
        // use-case orchestration
    }
}
```

Mapping from the old architecture:

```text
OLD                         CLEAN ARCHITECTURE

UserService interface   →   UserUseCase / specific UseCase
UserServiceImpl         →   Application Service
```

Example:

```text
LoginUseCase
    ↑
LoginService
```

instead of:

```text
LoginService
    ↑
LoginServiceImpl
```

The `Impl` suffix is not required.

Prefer names that describe responsibility.

---

# 7. Output Ports

Whenever application logic needs something outside the application boundary, define an output port.

Examples:

```text
UserRepositoryPort
PaymentGatewayPort
EventPublisherPort
ObjectStoragePort
MediaServerPort
NotificationPort
PasswordVerifierPort
TokenProviderPort
```

Example:

```java
public interface WalletRepositoryPort {

    Optional<Wallet> findByUserId(UUID userId);

    Wallet save(Wallet wallet);
}
```

The application only knows this interface.

It must not know which technology implements it.

---

# 8. Infrastructure Layer

## Responsibility

The `infrastructure` layer contains technical implementations.

It answers:

> Which technology is used to make the application work?

Infrastructure can contain:

```text
infrastructure/
├── persistence/
├── messaging/
├── cache/
├── client/
├── security/
├── storage/
├── config/
└── scheduler/
```

Only create packages that are actually required.

---

# 9. Persistence

Database implementation belongs to infrastructure.

Recommended structure:

```text
infrastructure/
└── persistence/
    ├── entity/
    ├── repository/
    ├── adapter/
    └── mapper/
```

Example:

```text
entity/
└── UserJpaEntity.java

repository/
└── SpringDataUserRepository.java

adapter/
└── UserRepositoryAdapter.java

mapper/
└── UserPersistenceMapper.java
```

JPA entities belong here.

Example:

```java
@Entity
@Table(name = "users")
public class UserJpaEntity {

    @Id
    private UUID id;

    private String email;
}
```

Spring Data repository:

```java
public interface SpringDataUserRepository
        extends JpaRepository<UserJpaEntity, UUID> {
}
```

Adapter:

```java
@Repository
@RequiredArgsConstructor
public class UserRepositoryAdapter
        implements UserRepositoryPort {

    private final SpringDataUserRepository repository;
    private final UserPersistenceMapper mapper;

    @Override
    public User save(User user) {
        UserJpaEntity entity = mapper.toEntity(user);
        UserJpaEntity saved = repository.save(entity);

        return mapper.toDomain(saved);
    }
}
```

Flow:

```text
Application
    ↓
UserRepositoryPort
    ↑
UserRepositoryAdapter
    ↓
SpringDataUserRepository
    ↓
UserJpaEntity
    ↓
PostgreSQL
```

---

# 10. Domain Model vs JPA Entity

They are different concepts.

Example:

```text
domain/model/User.java
```

represents the business model.

```text
infrastructure/persistence/entity/UserJpaEntity.java
```

represents how the data is persisted.

Fields may appear duplicated.

This duplication is intentional when persistence isolation provides value.

Example:

```text
User
    ↕ mapper
UserJpaEntity
```

However, avoid unnecessary duplication for extremely simple CRUD models.

RealLive follows pragmatic Clean Architecture.

If a domain is trivial and separating models provides no real benefit, simplification is allowed.

Complex domains such as:

```text
Wallet
Donation
Refund
Withdrawal
Stream lifecycle
```

should strongly prefer domain models separated from persistence models.

---

# 11. Messaging

Broker-specific implementations belong to infrastructure.

Example:

```text
infrastructure/
└── messaging/
    └── kafka/
        ├── producer/
        ├── consumer/
        ├── event/
        └── config/
```

Application port:

```java
public interface StreamEventPublisherPort {

    void publishStarted(StreamSession stream);
}
```

Kafka implementation:

```java
@Component
@RequiredArgsConstructor
public class KafkaStreamEventPublisher
        implements StreamEventPublisherPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publishStarted(StreamSession stream) {
        // publish Kafka event
    }
}
```

Changing:

```text
Kafka → RabbitMQ
```

should primarily affect infrastructure.

Application business logic should not require modification if the business behavior remains unchanged.

---

# 12. Redis

Redis-specific code belongs to infrastructure.

Example:

```text
infrastructure/
└── cache/
    └── redis/
        └── RedisViewerCounter.java
```

Application defines behavior:

```java
public interface ViewerCounterPort {

    long increment(UUID streamId);

    long decrement(UUID streamId);
}
```

Infrastructure implements it using Redis.

Do not expose `RedisTemplate` or Redis data structures to application/domain.

---

# 13. External Service Calls

Outgoing HTTP calls to other microservices or third-party APIs belong to infrastructure.

Examples:

- OpenFeign
- WebClient
- RestClient
- payment provider SDK
- MediaMTX API
- external email API

Example:

```text
Payment Service
    ↓
UserServicePort
    ↑
UserServiceAdapter
    ↓
UserFeignClient
    ↓
User Service
```

Application:

```java
public interface UserServicePort {

    UserInfo getUser(UUID userId);
}
```

Infrastructure:

```java
@FeignClient(
    name = "user-service",
    url = "${services.user.url}"
)
public interface UserFeignClient {

    @GetMapping("/internal/users/{id}")
    UserResponse getUser(@PathVariable UUID id);
}
```

Adapter:

```java
@Component
@RequiredArgsConstructor
public class UserServiceAdapter
        implements UserServicePort {

    private final UserFeignClient client;

    @Override
    public UserInfo getUser(UUID userId) {
        UserResponse response = client.getUser(userId);

        return new UserInfo(
            response.id(),
            response.username()
        );
    }
}
```

Rule:

```text
Incoming HTTP
→ presentation

Outgoing HTTP
→ infrastructure
```

---

# 14. Third-Party Providers

Third-party services must be hidden behind application ports whenever practical.

Example payment port:

```java
public interface PaymentGatewayPort {

    PaymentResult createPayment(
        CreatePaymentCommand command
    );
}
```

Infrastructure implementation:

```text
PayOsPaymentAdapter
```

or:

```text
MomoPaymentAdapter
```

Changing:

```text
payOS → MoMo
```

should primarily require replacing the infrastructure adapter.

Business logic such as:

```text
payment completed
→ credit coins
→ create wallet transaction
```

must remain in application/domain.

---

# 15. Presentation Layer

## Responsibility

The presentation layer handles incoming communication.

It answers:

> How does the outside world invoke the application?

Examples:

- REST controllers
- HTTP request DTOs
- HTTP response DTOs
- WebSocket entry points
- API validation
- HTTP exception mapping

Recommended structure:

```text
presentation/
└── rest/
    ├── controller/
    ├── request/
    └── response/
```

Example:

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;

    @PostMapping("/login")
    public LoginResponse login(
            @RequestBody LoginRequest request
    ) {
        LoginResult result = loginUseCase.execute(
            new LoginCommand(
                request.email(),
                request.password()
            )
        );

        return LoginResponse.from(result);
    }
}
```

Controllers MUST NOT:

- Call JPA repositories directly.
- Use KafkaTemplate directly.
- Use RedisTemplate directly.
- Call OpenFeign directly.
- Contain domain business rules.
- Perform complex orchestration.

Controllers should:

```text
Receive request
→ validate / map
→ call use case
→ map result
→ return response
```

---

# 16. Common Package

Code used across several modules inside the SAME microservice may be placed under:

```text
common/
```

Example:

```text
common/
├── infrastructure/
│   ├── config/
│   └── security/
│
├── presentation/
│   └── exception/
│
└── util/
```

Example:

```text
payment-service/
│
├── wallet/
├── donation/
├── withdrawal/
│
├── common/
│   ├── infrastructure/
│   │   ├── config/
│   │   │   ├── KafkaConfig.java
│   │   │   ├── RedisConfig.java
│   │   │   └── WebClientConfig.java
│   │   │
│   │   └── security/
│   │       └── SecurityConfig.java
│   │
│   └── presentation/
│       └── exception/
│           └── GlobalExceptionHandler.java
│
└── PaymentServiceApplication.java
```

Rule:

```text
Used by one feature
→ keep inside that feature

Used by multiple features in the same microservice
→ common
```

`common` MUST NOT become a dumping ground.

Bad:

```text
common/
├── UserService.java
├── DonationRepository.java
├── PaymentMapper.java
├── RandomHelper.java
└── EverythingUtils.java
```

Business-specific classes must remain inside their business feature.

---

# 17. Configuration

Global technical configuration for a microservice belongs to:

```text
common/infrastructure/config/
```

Examples:

```text
KafkaConfig.java
RedisConfig.java
RabbitMqConfig.java
WebClientConfig.java
ObjectMapperConfig.java
```

Feature-specific configuration stays in the feature infrastructure.

Example:

```text
payment/
└── infrastructure/
    └── payos/
        ├── PayOsClient.java
        └── PayOsConfig.java
```

---

# 18. Security

Spring Security implementation belongs to infrastructure.

Example:

```text
common/
└── infrastructure/
    └── security/
        ├── SecurityConfig.java
        ├── JwtAuthenticationConverter.java
        └── CurrentUserProvider.java
```

Classes using:

```java
SecurityFilterChain
HttpSecurity
JwtDecoder
JwtEncoder
GrantedAuthority
Authentication
PasswordEncoder
```

normally belong here.

Example:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {
        // configure authentication,
        // authorization and CORS
    }
}
```

Differentiate infrastructure security from business authorization.

Technical authorization:

```text
Does this request contain a valid JWT?
```

belongs to:

```text
infrastructure/security
```

Business authorization:

```text
Does this streamer own this channel?
Can this admin approve this withdrawal?
Can this user refund this donation?
```

belongs to:

```text
application/domain
```

---

# 19. CORS

For RealLive:

```text
Web / Android
      ↓
API Gateway
      ↓
Microservices
```

Browser CORS should normally be handled at the API Gateway when all external HTTP requests pass through the gateway.

Do not duplicate CORS configuration in every service unless the service is intentionally exposed directly.

---

# 20. Exception Handling

Exceptions are separated by responsibility.

## Domain exceptions

Example:

```text
wallet/domain/exception/
└── InsufficientBalanceException.java
```

Used for business rule violations.

Examples:

```text
InsufficientBalanceException
DonationAlreadyRefundedException
InvalidStreamStateException
```

## Application exceptions

Example:

```text
application/exception/
```

Used for failures related to application use cases.

Examples:

```text
UserNotFoundException
StreamNotFoundException
PaymentTransactionNotFoundException
```

## GlobalExceptionHandler

Spring HTTP exception mapping belongs to presentation.

Example:

```text
common/
└── presentation/
    └── exception/
        ├── GlobalExceptionHandler.java
        └── ErrorResponse.java
```

Example:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handle(
            UserNotFoundException exception
    ) {
        // map application exception to HTTP response
    }
}
```

The handler converts:

```text
Domain/Application Exception
        ↓
HTTP Status + ErrorResponse
```

Each microservice owns its own exception handling.

Do NOT create one runtime `GlobalExceptionHandler` shared by every microservice.

---

# 21. Microservice Independence

Each microservice is an independently deployable application.

Example:

```text
user-service
payment-service
stream-service
vod-service
```

Each service may own its own:

```text
SecurityConfig
KafkaConfig
RedisConfig
GlobalExceptionHandler
database migrations
application configuration
```

Do not introduce a shared library merely to eliminate a few duplicate classes.

Cross-service shared libraries should only be introduced when duplication becomes significant and the shared contract is stable.

Avoid creating a distributed monolith through shared code.

---

# 22. Recommended Login Example

Example structure:

```text
auth/
│
├── domain/
│   ├── model/
│   │   └── User.java
│   └── exception/
│       └── InvalidCredentialsException.java
│
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── LoginUseCase.java
│   │   │
│   │   └── out/
│   │       ├── UserRepositoryPort.java
│   │       ├── PasswordVerifierPort.java
│   │       └── TokenProviderPort.java
│   │
│   ├── service/
│   │   └── LoginService.java
│   │
│   └── dto/
│       ├── LoginCommand.java
│       └── LoginResult.java
│
├── infrastructure/
│   ├── persistence/
│   │   ├── entity/
│   │   │   └── UserJpaEntity.java
│   │   ├── repository/
│   │   │   └── SpringDataUserRepository.java
│   │   ├── adapter/
│   │   │   └── UserRepositoryAdapter.java
│   │   └── mapper/
│   │       └── UserPersistenceMapper.java
│   │
│   └── security/
│       ├── JwtTokenProviderAdapter.java
│       └── BCryptPasswordVerifierAdapter.java
│
└── presentation/
    └── rest/
        ├── AuthController.java
        ├── request/
        │   └── LoginRequest.java
        └── response/
            └── LoginResponse.java
```

Flow:

```text
POST /auth/login
        ↓
AuthController
        ↓
LoginUseCase
        ↓
LoginService
        ↓
UserRepositoryPort
        ↑
UserRepositoryAdapter
        ↓
SpringDataUserRepository
        ↓
PostgreSQL
```

Authentication:

```text
LoginService
     ↓
PasswordVerifierPort
     ↑
BCryptPasswordVerifierAdapter
```

Token generation:

```text
LoginService
     ↓
TokenProviderPort
     ↑
JwtTokenProviderAdapter
```

---

# 23. Mapping From Previous 3-Layer Architecture

Previous RealLive-style architecture:

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
Entity
```

Clean Architecture mapping:

```text
OLD                         CLEAN

Controller              →   presentation

Service interface       →   application port/in UseCase

ServiceImpl             →   application service

Repository interface    →   application port/out

RepositoryImpl          →   infrastructure adapter

JpaRepository           →   infrastructure persistence

@Entity                  →   infrastructure persistence/entity

Business entity/model   →   domain/model

KafkaTemplate            →   infrastructure messaging

RedisTemplate            →   infrastructure cache

OpenFeign                →   infrastructure client

WebClient                →   infrastructure client

SecurityFilterChain      →   common/infrastructure/security

GlobalExceptionHandler  →   common/presentation/exception
```

---

# 24. Dependency Rules

The following rules are mandatory.

## Domain

Domain may depend on:

```text
Java standard library
Other domain objects in the same bounded business area
```

Domain MUST NOT depend on:

```text
presentation
infrastructure
Spring MVC
Spring Security
JPA
Kafka
Redis
Feign
WebClient
external providers
```

## Application

Application may depend on:

```text
domain
application DTOs
application ports
```

Application MUST NOT directly depend on:

```text
JpaRepository
KafkaTemplate
RedisTemplate
FeignClient
WebClient
S3Client
MediaMTX client
payOS SDK
```

Pragmatic exception:

Spring annotations such as:

```java
@Service
@Transactional
```

may be used in application services when this keeps Spring Boot implementation simple.

Do not allow those annotations to leak technical APIs into the business logic.

## Infrastructure

Infrastructure may depend on:

```text
application
domain
Spring
JPA
Kafka
Redis
Feign
WebClient
external SDKs
```

Infrastructure implements application/domain ports.

## Presentation

Presentation may depend on:

```text
application
presentation DTOs
Spring MVC / WebSocket
```

Presentation MUST NOT directly use persistence implementations.

---

# 25. Avoid Interface Spam

Clean Architecture does NOT mean:

> Every class must have an interface.

Create interfaces where there is an architectural boundary.

Good candidates:

```text
UseCase
RepositoryPort
PaymentGatewayPort
MediaServerPort
ObjectStoragePort
EventPublisherPort
ExternalServicePort
PasswordVerifierPort
TokenProviderPort
```

Usually unnecessary:

```text
UserMapperInterface
DateUtilsInterface
SimpleCalculatorInterface
StringFormatterInterface
```

Do not add interfaces without architectural value.

---

# 26. Avoid Empty Architecture

Do not create folders just to satisfy a template.

Bad:

```text
category/
├── domain/
├── application/
├── infrastructure/
└── presentation/
```

when three folders are empty.

Only create architectural packages when code actually requires them.

Simple CRUD features may use a lighter structure.

Complex business features should use stronger separation.

---

# 27. When to Use Strong Domain Separation

Strong Clean Architecture separation is particularly useful for RealLive modules such as:

```text
Payment
Wallet
Donation
Refund
Withdrawal
Stream lifecycle
Authentication
```

because they contain:

- Important business invariants.
- State transitions.
- Transactions.
- Multiple infrastructure dependencies.
- External APIs.
- Events.
- Concurrency concerns.

For simple lookup or CRUD functionality, avoid unnecessary ceremony.

---

# 28. RealLive Stream Example

Application may define:

```java
public interface MediaServerPort {

    void terminateStream(UUID streamId);
}
```

Infrastructure implementation:

```text
MediaMtxAdapter
```

Later:

```text
MediaMTX → SRS
```

only the infrastructure implementation should need major changes if the business behavior remains the same.

Application:

```text
EndStreamUseCase
```

still means:

```text
Validate stream state
→ transition stream to ENDED
→ persist
→ terminate media stream
→ publish StreamEnded event
```

---

# 29. RealLive Payment Example

Application/domain business logic:

```text
Payment becomes PAID
→ credit user wallet
→ create wallet transaction
→ publish payment event
```

Infrastructure may currently use:

```text
payOS
PostgreSQL
Kafka
Redis
```

If infrastructure changes to:

```text
MoMo
MongoDB
RabbitMQ
different cache
```

business behavior should remain isolated whenever possible.

Technical replacement:

```text
payOS → MoMo
```

should primarily affect infrastructure.

Business change:

```text
100,000 VND now grants bonus coins for VIP users
```

must affect domain/application because this is a business requirement change.

---

# 30. Naming Conventions

Preferred names:

```text
LoginUseCase
LoginService

WalletRepositoryPort
WalletRepositoryAdapter

PaymentGatewayPort
PayOsPaymentAdapter

StreamEventPublisherPort
KafkaStreamEventPublisher

MediaServerPort
MediaMtxAdapter

UserJpaEntity
SpringDataUserRepository
UserPersistenceMapper

LoginRequest
LoginResponse
LoginCommand
LoginResult
```

Avoid vague names:

```text
Helper
Manager
Utils
Processor
CommonService
BaseService
HandlerImpl
```

unless their responsibility is genuinely clear.

---

# 31. Agent Coding Rules

Coding agents working on RealLive MUST follow these rules.

1. Determine the target microservice first.

2. Determine the business feature/module before creating files.

3. Do not create global top-level `controller`, `service`, `repository`, and `entity` packages for the entire service.

4. Prefer:

```text
feature/
├── domain/
├── application/
├── infrastructure/
└── presentation/
```

5. Do not force all four folders when unnecessary.

6. Keep domain framework-independent.

7. Do not place `@Entity` on domain models when the feature uses strict domain/persistence separation.

8. JPA entities belong to:

```text
infrastructure/persistence/entity
```

9. Spring Data repositories belong to infrastructure.

10. Repository contracts used by application belong to application output ports.

11. Controllers belong to presentation.

12. Use-case orchestration belongs to application.

13. Core business invariants belong to domain.

14. Kafka, Redis, OpenFeign, WebClient, S3, payment providers, and media-server implementations belong to infrastructure.

15. Incoming REST requests belong to presentation.

16. Outgoing REST calls belong to infrastructure.

17. Global Spring configuration belongs to:

```text
common/infrastructure
```

when shared by several modules.

18. `SecurityConfig` belongs to:

```text
common/infrastructure/security
```

19. `GlobalExceptionHandler` belongs to:

```text
common/presentation/exception
```

20. Business exceptions belong close to the business layer that owns them.

21. Do not put business code into `common`.

22. Do not create abstractions with no clear reason.

23. Do not create interfaces merely because Clean Architecture is being used.

24. Preserve microservice independence.

25. Avoid cross-service database access.

26. Never create database foreign keys between databases owned by different microservices.

27. Cross-service references use IDs and service contracts.

28. Use Kafka for asynchronous domain events where appropriate.

29. Use REST/OpenFeign/WebClient only when synchronous communication is actually required.

30. Do not expose technical infrastructure types beyond their adapter boundary.

---

# 32. Final Architecture Principle

The most important rule is not the folder structure.

Clean Architecture exists to keep business logic independent from replaceable technical details.

Remember:

```text
domain
= business rules

application
= use-case orchestration

infrastructure
= technical implementation

presentation
= incoming interface
```

And:

```text
Business
does not depend on
Database / Kafka / Redis / HTTP / Framework
```

Instead:

```text
Business defines what it needs
        ↓
Ports / Interfaces
        ↑
Infrastructure implements them
```

The goal is not maximum abstraction.

The goal is:

- Clear responsibilities.
- Stable business logic.
- Replaceable infrastructure.
- Easier testing.
- Easier maintenance.
- Consistent code organization.
- Independent microservices.

When architecture and simplicity conflict, choose the simplest design that still preserves important architectural boundaries.