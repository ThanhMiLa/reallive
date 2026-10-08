package com.reallive.userservice.common.util;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserValidatorTest {

    @Test
    void validateActiveAcceptsActiveUsers() {
        UserEntity user = UserEntity.builder().status(UserStatus.ACTIVE).build();

        assertDoesNotThrow(() -> UserValidator.validateActive(user));
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void validateActiveRejectsInactiveUsers(UserStatus status) {
        UserEntity user = UserEntity.builder().status(status).build();

        AppException exception = assertThrows(AppException.class, () -> UserValidator.validateActive(user));

        assertEquals(ErrorCode.USER_NOT_ACTIVE, exception.getErrorCode());
    }
}
