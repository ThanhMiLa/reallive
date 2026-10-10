package com.reallive.notificationservice.common.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppExceptionTest {

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void propagatesTheSelectedErrorCodeAndItsClientMessage(ErrorCode errorCode) {
        var exception = assertThrows(AppException.class, () -> {
            throw new AppException(errorCode);
        });
        assertSame(errorCode, exception.getErrorCode());
        assertEquals(errorCode.getMessage(), exception.getMessage());
    }
}
