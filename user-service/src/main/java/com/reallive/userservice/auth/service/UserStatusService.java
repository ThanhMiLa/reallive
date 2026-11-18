package com.reallive.userservice.auth.service;

import com.reallive.userservice.auth.dto.response.UserIdentityResponse;
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

    /**
     * Searches account identities by a case-insensitive display-name substring.
     *
     * @param displayName the literal display-name fragment
     * @param page zero-based page number
     * @param size requested page size
     * @return matching identities and paging information, including an empty page when none match
     * @throws AppException if the fragment or pagination is invalid
     */
    UserIdentityResponse.Page searchIdentities(String displayName, int page, int size);
}
