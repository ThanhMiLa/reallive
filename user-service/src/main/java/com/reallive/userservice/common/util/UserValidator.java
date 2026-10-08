package com.reallive.userservice.common.util;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;

public final class UserValidator {

    private UserValidator() {
    }

    public static void validateActive(UserEntity user) {
        if (!user.isActive()) {
            throw new AppException(ErrorCode.USER_NOT_ACTIVE);
        }
    }
}
