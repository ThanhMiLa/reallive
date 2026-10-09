---
name: service-javadoc
description: Write or review mandatory English Javadoc for every method declared in a Java service interface in this repository. Apply when creating or editing interfaces in service packages.
---

# Service Interface Javadoc

Every method declared in a Java interface in a `service` package or its subpackages must have an English Javadoc block immediately above the declaration, even when its name appears self-explanatory. Apply this to all declared methods in a service interface being created or edited, including default and static methods.

## Required Content

- Explain the method's purpose and observable contract in a concise opening sentence.
- Add `@param` for every parameter, including method type parameters when present.
- Add `@return` for non-void methods. Explain meaningful statuses, flags, nullability or absence when applicable.
- Add `@throws` for exceptions callers need to handle or contractually important failure conditions. Match the actual behavior; do not invent guarantees or exception types.
- Describe relevant side effects and constraints, such as persistence, expiration, atomicity, ownership, idempotency or asynchronous acknowledgement. Include them only when they apply to that method.
- Use `{@link ...}` for related methods or result constants and `{@code ...}` for literal values where useful.

Keep documentation at the interface's contract level. Explain what callers can rely on without narrating implementation steps. Do not duplicate interface Javadoc in implementations unless they introduce additional behavior that needs explanation.

When a signature or behavior changes, update its Javadoc in the same change. Review every declared method in the affected service interface before finishing. Do not expand a focused task into a repository-wide documentation rewrite unless requested.

```java
public interface EmailVerificationOtpStore {

    /**
     * Atomically compares the OTP hash and deletes the key if it matches.
     *
     * <p>A mismatch preserves the key and its TTL.</p>
     *
     * @param userId the user verifying their email
     * @param otpHash the HMAC computed from the submitted OTP and user identity
     * @return VALID if the key was consumed, INVALID if the hash differs,
     *         or NOT_FOUND if the key does not exist
     */
    EmailVerificationOtpCheckResult consume(UUID userId, String otpHash);
}
```
