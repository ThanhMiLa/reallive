package com.reallive.userservice.auth.service;

import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.common.exception.AppException;

import java.util.UUID;

public interface UserStatusService {

    /**
     * Retrieves the current account status for an existing user.
     *
     * @param userId ID of the account to look up
     * @return the account status
     * @throws AppException if the user does not exist
     */
    UserStatus getStatus(UUID userId);
}
