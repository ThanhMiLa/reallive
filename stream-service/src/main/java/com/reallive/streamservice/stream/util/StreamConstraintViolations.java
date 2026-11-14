package com.reallive.streamservice.stream.util;

import org.hibernate.exception.ConstraintViolationException;

import java.util.Locale;

public final class StreamConstraintViolations {

    private StreamConstraintViolations() {}

    public static boolean isUniqueViolation(Throwable exception, String... constraintNames) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                String sqlState = violation.getSQLState();
                if ("23505".equals(sqlState)) {
                    if (constraintNames == null || constraintNames.length == 0) {
                        return true;
                    }
                    String name = violation.getConstraintName();
                    if (name == null) {
                        return true;
                    }
                    String lower = name.toLowerCase(Locale.ROOT);
                    for (String expected : constraintNames) {
                        if (lower.contains(expected.toLowerCase(Locale.ROOT))) {
                            return true;
                        }
                    }
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
