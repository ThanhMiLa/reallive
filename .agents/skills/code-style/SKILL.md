---
name: code-style
description: Apply this repository's Java code-organization, Lombok, record formatting, repository query, naming, import, and commenting conventions when writing, editing, or reviewing Java code.
---

# Java Code Style Conventions

Apply these rules only to Java code. Produce Java code that is cohesive, readable, and easy to change. Follow the conventions below in addition to the closest existing code in the backend module.

## Design and Organization

- Follow SOLID principles. Give each class and method one clear responsibility.
- Keep Java classes cohesive. Split unrelated responsibilities into appropriate classes, services, mappers, or utility classes instead of accumulating them in one file.
- When a method has several distinct logical steps, extract meaningful steps into small private helper methods. The public or entry method should coordinate those steps and make the flow easy to read.
- Do not extract trivial one-line wrappers solely to create more methods. Extract when it clarifies a meaningful business rule, validation, transformation, side effect, or decision.

```java
public OrderResponse createOrder(CreateOrderRequest request) {
    validateRequest(request);
    OrderEntity order = createPendingOrder(request);
    chargeWallet(order);
    return orderMapper.toResponse(order);
}

private void validateRequest(CreateOrderRequest request) {
    // Validation logic
}
```

## Lombok

### Controllers and Services

- Prefer `@RequiredArgsConstructor` together with `@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)` for controllers, service implementations, and similar Spring beans with injected dependencies.
- Declare dependency fields using only their type and name. Let Lombok supply `private final` and generate the constructor; Spring performs constructor injection. Do not write a boilerplate constructor or add `@Autowired` when the generated constructor is the only constructor.
- Use an explicit constructor only when initialization logic or injection annotations cannot be expressed correctly with the Lombok setup.

```java
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamServiceImpl implements StreamService {

    StreamRepository streamRepository;
    ChannelService channelService;

    @NonFinal
    @Value("${stream.playback-base-url}")
    String playbackBaseUrl;
}
```

- For fields populated from `application.properties` or `application.yml` with Spring's `@Value`, add `lombok.experimental.NonFinal` to override `makeFinal = true` and keep the field out of the generated required-arguments constructor.
- `@NotNull` is a null-validation constraint, not a replacement for `@NonFinal`. Use it when a value must be non-null and ensure Bean Validation is actually invoked; adding it to an arbitrary `@Value` field does not automatically validate configuration. Do not add Lombok's `@NonNull` to a field intended for `@Value` field injection, because it makes an uninitialized field a required constructor argument.
- Import Spring's `org.springframework.beans.factory.annotation.Value` for configuration injection, not Lombok's `@Value`.

### Entities and DTOs

- Prefer `@Data`, `@Builder`, `@AllArgsConstructor`, `@NoArgsConstructor`, and `@FieldDefaults(level = AccessLevel.PRIVATE)` for entity and DTO classes.
- Declare fields using only their type and name, plus any persistence, serialization, or validation annotations. Let Lombok generate accessors, constructors, and the builder instead of writing boilerplate manually.
- Keep these fields mutable by default; do not add `makeFinal = true` to this pattern.
- For entities with relationships or special identity rules, customize the generated `toString`, `equals`, and `hashCode` as needed to preserve correct behavior.

```java
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class StreamResponse {

    Long id;
    String title;
    String playbackUrl;
}
```

## Records

Format Java `record` declarations with each component (field/parameter) on its own separate line to ensure readability and clean diffs.

- **One component per line:** Always declare each record component on a new line. Never place all components inline on the header line or cram multiple components across arbitrarily wrapped lines.
- **Indentation:** Indent each component with standard continuation indentation (8 spaces, or two levels of indentation relative to the record declaration).
- **Closing parenthesis:** Place the closing parenthesis `)` on its own line after the last component, followed immediately by `{}` (if the record has no body) or `{` (if the record defines methods, constructors, or overrides).
- **Component annotations:** When record components require validation or serialization annotations (such as `@NotNull`, `@Pattern`, or `@JsonProperty`), place each annotation on its own line preceding the component type or keep them cleanly formatted per component. Do not combine annotations and components across multiple fields on one line.

```java
// DO: Multi-line component declaration with closing parenthesis on a separate line
public record CreateSessionCommand(
        UUID channelId,
        UUID streamerId,
        UUID ownerUserId,
        UUID categoryId,
        String title,
        UUID creationRequestId
) {}
```

```java
// DO: Records with bodies (methods, overrides) follow the same multi-line component structure
public record StreamPlaybackAccessResponse(
        UUID sessionId,
        String playbackUrl,
        String readToken,
        Instant readExpiresAt
) {
    @Override
    public String toString() {
        return "StreamPlaybackAccessResponse[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
```

```java
// DON'T: Single-line declarations crowding all components together
public record StreamPlaybackAccessResponse(UUID sessionId, String playbackUrl, String readToken, Instant readExpiresAt) {
    @Override
    public String toString() {
        return "StreamPlaybackAccessResponse[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
```

```java
// DON'T: Arbitrary multi-component wrapping on the same line
public record StreamSessionResponse(UUID id, UUID channelId, String title, UUID categoryId,
        StreamVisibility visibility, StreamSessionStatus status, Instant startedAt, Instant endedAt,
        Instant reconnectDeadlineAt, Instant serverTime, boolean publishReady) {
}
```

## Repository Queries

- Write all JPQL and native SQL keywords in uppercase in repository queries, including queries in `@Query`, `@NamedQuery`, `@NamedNativeQuery`, and query strings in custom repository implementations.
- Apply this to every clause and keyword, for example `SELECT`, `DISTINCT`, `FROM`, `WHERE`, `JOIN`, `LEFT JOIN`, `ON`, `AND`, `OR`, `IN`, `IS NULL`, `GROUP BY`, `HAVING`, `ORDER BY`, `ASC`, `DESC`, `INSERT INTO`, `VALUES`, `UPDATE`, `SET`, and `DELETE`.
- Preserve the spelling and case of entity names, property names, table and column identifiers, aliases, parameter names, and string literals. Change keyword casing only; preserve query behavior.
- Put each main clause (`SELECT`, `FROM`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `UPDATE`, `SET`, `INSERT INTO`, `VALUES`, and `DELETE`) on its own line. Keep a short clause expression on that line; for a long projection or assignment list, put each item on its own continuation line.
- Put each `JOIN` on its own line. For multi-condition `ON` or `WHERE` expressions, put the first condition after the clause and each following `AND` or `OR` condition on a new line, indented consistently beneath the clause. Keep simple, short conditions together when that remains easy to scan.
- In Java text blocks, indent query lines consistently within the text block. Do not add line breaks inside string literals or expressions in ways that alter query semantics.

```java
@Query("""
        SELECT s
        FROM StreamSessionEntity s
        WHERE s.ownerUserId = :ownerUserId
          AND s.endedAt IS NULL
        ORDER BY s.startedAt DESC
        """)
List<StreamSessionEntity> findActiveSessionsByOwnerUserId(UUID ownerUserId);

@Query(value = """
        SELECT s.*
        FROM stream_sessions s
        WHERE s.owner_user_id = :ownerUserId
          AND s.ended_at IS NULL
        ORDER BY s.started_at DESC
        """, nativeQuery = true)
List<StreamSessionEntity> findActiveSessionsNative(UUID ownerUserId);
```

## Naming

- Name variables, methods, and fields in lower camel case, for example `getAllUsers`, `calculateTotalPrice`, and `isEligibleForEnrollment`.
- Use names that communicate intent and domain meaning. Avoid vague names such as `data`, `result`, `temp`, or single-letter variables except for conventional, short-lived loop indexes.
- Use PascalCase for Java classes, interfaces, enums, records, and annotations.

## Imports

- Declare imports at the top of the file using the language's normal import syntax.
- Refer to imported types by their class, function, or symbol name in the code body. Do not use fully-qualified package paths inline when a normal import is appropriate.
- Remove unused imports and follow the existing import ordering and grouping in the edited module.

## Comments and Documentation

- Every method declared in a Java service interface must have English Javadoc. Follow [service-javadoc](../service-javadoc/SKILL.md) for the required contract documentation.
- Do not add comments that merely restate obvious code or narrate every line inside a method body.
- Prefer clear method names and small methods over explanatory inline comments.
- When a public API, complex method, or non-obvious business rule needs explanation, add a concise Javadoc-style comment directly above the relevant declaration. Document intent, important constraints, and non-obvious side effects rather than implementation trivia.

```java
/**
 * Credits the wallet exactly once after a verified payment callback.
 */
private void creditWalletForVerifiedPayment(PaymentTransactionEntity transaction) {
    // Implementation
}
```
