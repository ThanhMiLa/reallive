package com.reallive.userservice.user.dto.response;

import java.time.Duration;

/** Acquired cooldown has zero retry delay; rejected requests carry the remaining cooldown. */
public record EmailVerificationCooldownResult(
        boolean acquired,
        Duration retryAfter
) {}
