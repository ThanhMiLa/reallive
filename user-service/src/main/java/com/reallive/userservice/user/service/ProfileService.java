package com.reallive.userservice.user.service;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.dto.request.UpdateProfileRequest;
import com.reallive.userservice.user.dto.response.UserProfileResponse;

import java.util.UUID;

public interface ProfileService {

    /**
     * Retrieves the profile and account details of the specified active user.
     *
     * @param userId the authenticated user whose profile is requested
     * @return the user's profile and account details; avatar URL and date of birth may be {@code null}
     * @throws AppException with {@link ErrorCode#PROFILE_NOT_FOUND} if the profile does not exist,
     *                      or {@link ErrorCode#USER_NOT_ACTIVE} if the user is inactive
     */
    UserProfileResponse getMyProfile(UUID userId);

    /**
     * Persists changes to the specified active user's profile and returns the updated details.
     *
     * <p>Fields set to {@code null} remain unchanged, and at least one field must be supplied.
     * Display names are stripped of leading and trailing whitespace. A supplied avatar must be
     * a readable JPEG, PNG or WebP image with at most 16 million pixels.</p>
     *
     * <p>Avatar replacement uploads a new image and updates its URL. Cleanup of the previous
     * managed avatar after commit, or the new upload after rollback, is best effort; cleanup
     * failure does not fail a committed update.</p>
     *
     * @param userId the authenticated user whose profile is updated
     * @param request the profile fields to change, with {@code null} fields left unchanged
     * @return the updated profile and account details; avatar URL and date of birth may be {@code null}
     * @throws AppException with {@link ErrorCode#PROFILE_UPDATE_EMPTY} if no field is supplied,
     *                      {@link ErrorCode#PROFILE_NOT_FOUND} if the profile does not exist,
     *                      {@link ErrorCode#USER_NOT_ACTIVE} if the user is inactive,
     *                      {@link ErrorCode#AVATAR_INVALID} if the avatar is invalid, or
     *                      {@link ErrorCode#AVATAR_UPLOAD_FAILED} if uploading the avatar fails
     * @throws IllegalStateException if the configured avatar URL exceeds the supported length
     */
    UserProfileResponse updateMyProfile(UUID userId, UpdateProfileRequest request);
}
