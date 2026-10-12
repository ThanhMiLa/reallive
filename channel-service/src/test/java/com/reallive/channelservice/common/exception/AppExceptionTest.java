package com.reallive.channelservice.common.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AppExceptionTest {

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void preservesBusinessErrorAndUsesItsClientFacingMessage(ErrorCode errorCode) {
        AppException exception = new AppException(errorCode);

        assertSame(errorCode, exception.getErrorCode());
        assertEquals(errorCode.getMessage(), exception.getMessage());
    }
}
