---
name: code-style
description: Apply this repository's Java code-organization, Lombok, naming, import, and commenting conventions when writing, editing, or reviewing Java code.
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
