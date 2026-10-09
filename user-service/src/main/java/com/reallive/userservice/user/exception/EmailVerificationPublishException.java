package com.reallive.userservice.user.exception;

import lombok.Getter;

/** Indicates whether a failed publication may already have reached Kafka. */
@Getter
public class EmailVerificationPublishException extends RuntimeException {

    private final boolean publicationUncertain;

    public EmailVerificationPublishException(boolean publicationUncertain, Throwable cause) {
        super("Unable to publish email verification request", cause);
        this.publicationUncertain = publicationUncertain;
    }
}
