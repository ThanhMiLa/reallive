---
name: code-style
description: Apply this repository's Java code-organization, naming, import, and commenting conventions when writing, editing, or reviewing Java code.
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

## Naming

- Name variables, methods, and fields in lower camel case, for example `getAllUsers`, `calculateTotalPrice`, and `isEligibleForEnrollment`.
- Use names that communicate intent and domain meaning. Avoid vague names such as `data`, `result`, `temp`, or single-letter variables except for conventional, short-lived loop indexes.
- Use PascalCase for Java classes, interfaces, enums, records, and annotations.

## Imports

- Declare imports at the top of the file using the language's normal import syntax.
- Refer to imported types by their class, function, or symbol name in the code body. Do not use fully-qualified package paths inline when a normal import is appropriate.
- Remove unused imports and follow the existing import ordering and grouping in the edited module.

## Comments and Documentation

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
