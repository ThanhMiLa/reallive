package com.reallive.userservice.common.security;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class TokenIssuancePolicy {

    public void requireEligible(UserEntity user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.USER_NOT_ACTIVE);
        }
    }
}
