package com.reallive.userservice.common.util;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserValidatorTest {

    @Test
    void validateEmailNotVerifiedAcceptsUnverifiedEmail() {
        UserEntity user = UserEntity.builder().emailVerified(false).build();
        assertDoesNotThrow(() -> UserValidator.validateEmailNotVerified(user));
    }

    @Test
    void validateEmailNotVerifiedRejectsVerifiedEmail() {
        UserEntity user = UserEntity.builder().emailVerified(true).build();
        AppException exception = assertThrows(AppException.class,
                () -> UserValidator.validateEmailNotVerified(user));
        assertEquals(ErrorCode.EMAIL_ALREADY_VERIFIED, exception.getErrorCode());
    }

    @Test
    void validateEmailVerifiedAcceptsVerifiedEmail() {
        UserEntity user = UserEntity.builder().emailVerified(true).build();
        assertDoesNotThrow(() -> UserValidator.validateEmailVerified(user));
    }

    @Test
    void validateEmailVerifiedRejectsUnverifiedEmail() {
        UserEntity user = UserEntity.builder().emailVerified(false).build();
        AppException exception = assertThrows(AppException.class,
                () -> UserValidator.validateEmailVerified(user));
        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, exception.getErrorCode());
    }
}
