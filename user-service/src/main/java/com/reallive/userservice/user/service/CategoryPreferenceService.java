package com.reallive.userservice.user.service;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.dto.response.CategoryPreferencesResponse;

import java.util.UUID;

public interface CategoryPreferenceService {

    /**
     * Retrieves the category preferences for the specified active user.
     *
     * @param userId the unique identifier of the authenticated user whose preferences are requested
     * @return the category preferences containing the list of category identifiers chosen by the user,
     *         or an empty list if no preferences have been configured
     * @throws AppException with {@link ErrorCode#USER_NOT_FOUND} if the user does not exist,
     *                      or {@link ErrorCode#USER_NOT_ACTIVE} if the user account is not active
     */
    CategoryPreferencesResponse getCategoryPreferences(UUID userId);
}
