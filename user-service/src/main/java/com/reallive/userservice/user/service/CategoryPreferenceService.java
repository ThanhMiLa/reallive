package com.reallive.userservice.user.service;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.dto.request.UpdateCategoryPreferencesRequest;
import com.reallive.userservice.user.dto.response.CategoryPreferencesResponse;

import java.util.UUID;

public interface CategoryPreferenceService {

    /**
     * Retrieves the category preferences for the specified authenticated user.
     *
     * @param userId the unique identifier of the authenticated user whose preferences are requested
     * @return the category preferences containing the list of category identifiers chosen by the user,
     *         or an empty list if no preferences have been configured
     * @throws AppException with {@link ErrorCode#USER_NOT_FOUND} if the user does not exist
     */
    CategoryPreferencesResponse getCategoryPreferences(UUID userId);

    /**
     * Replaces the category preferences for the specified authenticated user with the supplied categories.
     *
     * <p>Duplicate category identifiers in the request are deduplicated while preserving order.
     * All supplied category identifiers are verified with Channel Service to ensure they exist and
     * are active before persisting. Existing category preferences are replaced atomically.</p>
     *
     * @param userId the unique identifier of the authenticated user whose preferences are being updated
     * @param request the request containing the list of desired category identifiers
     * @return the updated category preferences containing the persisted category identifiers
     * @throws AppException with {@link ErrorCode#USER_NOT_FOUND} if the user does not exist,
     *                      {@link ErrorCode#CATEGORY_NOT_FOUND} if one or more categories do not exist,
     *                      {@link ErrorCode#CATEGORY_NOT_ACTIVE} if one or more categories are not active,
     *                      or {@link ErrorCode#CHANNEL_SERVICE_UNAVAILABLE} if Channel Service cannot be reached
     */
    CategoryPreferencesResponse updateCategoryPreferences(UUID userId, UpdateCategoryPreferencesRequest request);
}
