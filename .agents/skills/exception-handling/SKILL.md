---
name: exception-handling
description: Apply this repository's common AppException and ErrorCode conventions when adding, refactoring, or reviewing application error handling in Java backend services.
---

# Java Exception Handling

Apply these rules to Java backend services. Keep application exception definitions and HTTP error handling in each service's `common.exception` package. Each service builds independently; do not introduce a shared cross-service module for this convention.

## Common Exception Model

- Use the module's `common.exception.AppException` for application errors and `common.exception.ErrorCode` for their definitions.
- Add a new enum constant to `ErrorCode` when a new error is needed. Do not create a separate exception class for each error or feature, including subclasses of `AppException`.
- Throw errors with `new AppException(ErrorCode.ERROR_NAME)`. Keep `AppException`'s constructor limited to `AppException(ErrorCode errorCode)`; do not add a constructor accepting `Throwable`, custom messages, or error-specific flags.
- Use `GlobalExceptionHandler` in `common.exception` to map `AppException` to the standard API response. Controllers should let application exceptions propagate to that handler instead of building error responses themselves.
- Framework and library exception types may still be caught where needed. Translate them into an appropriate `AppException` at the application boundary; do not create custom wrapper exception classes.

In User Service, the reference files are:

- `user-service/src/main/java/com/reallive/userservice/common/exception/AppException.java`
- `user-service/src/main/java/com/reallive/userservice/common/exception/ErrorCode.java`
- `user-service/src/main/java/com/reallive/userservice/common/exception/GlobalExceptionHandler.java`

## Adding Error Codes

- Reuse an existing enum constant when its meaning matches the failure. Otherwise add a descriptive `UPPER_SNAKE_CASE` constant with a unique numeric code, an English client-facing message, and the appropriate HTTP status.
- Follow the existing module's code numbering. Preserve existing numeric codes, messages, and HTTP statuses unless the requested change requires updating the API contract.
- When callers need to distinguish failure outcomes, define separate enum constants and check `exception.getErrorCode()`. Do not add a boolean flag or feature-specific field to `AppException`, and do not branch on exception message text.

For example, these existing User Service constants distinguish whether a failed email publication can safely trigger OTP cleanup:

```java
EMAIL_VERIFICATION_PUBLISH_FAILED(1028, "Unable to publish email verification request", HttpStatus.SERVICE_UNAVAILABLE),
EMAIL_VERIFICATION_PUBLISH_UNCERTAIN(1029, "Email verification publication outcome is unknown", HttpStatus.SERVICE_UNAVAILABLE);
```

Throw the corresponding application error using normal imports:

```java
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;

throw new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED);
```

## Refactoring Existing Exceptions

- Replace custom exception usages, imports, and Javadoc with `AppException` and the corresponding `ErrorCode` constants, then remove the unused exception class.
- Preserve behavior that depends on the failure type, such as cleanup, rollback, retry decisions, and thread interruption. Translate internal error codes into an existing public error code when needed to preserve the API contract.
- Update relevant regression tests to assert the error code, HTTP response, and meaningful side effects. Do not retain tests requiring a `Throwable` constructor or cause propagation.
