package com.reallive.userservice.user.model;

public enum EmailVerificationOtpCheckResult {
    VALID,
    INVALID,
    EXPIRED,
    ATTEMPTS_EXCEEDED,
    NOT_FOUND
}
