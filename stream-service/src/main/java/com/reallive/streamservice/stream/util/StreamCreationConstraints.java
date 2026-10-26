package com.reallive.streamservice.stream.util;

import org.hibernate.exception.ConstraintViolationException;

public final class StreamCreationConstraints {

    private StreamCreationConstraints() {}

    public static boolean isCreationConflict(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraint) {
                return "uq_stream_sessions_channel_slot".equals(constraint.getConstraintName())
                        || "uk_stream_sessions_owner_creation_request".equals(constraint.getConstraintName());
            }
            cause = cause.getCause();
        }
        return false;
    }
}
