package com.reallive.userservice.auth.service;

import com.reallive.userservice.auth.adapter.redis.TokenRevocation;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;

import java.util.List;
import java.util.UUID;

public interface AuthTokenService {

    /**
     * Issues a token pair and publishes auth state only after the surrounding transaction commits.
     *
     * @param user the eligible account with roles and permissions loaded
     * @param revocations tokens atomically consumed when publishing the auth state
     * @return the signed tokens, which must only be delivered after transaction completion
     * @throws AppException with {@link ErrorCode#USER_NOT_ACTIVE} when issuance is forbidden,
     *         {@link ErrorCode#UNAUTHENTICATED} for an already consumed token, or
     *         {@link ErrorCode#AUTH_STATE_UNAVAILABLE} when publication fails
     */
    AuthResponse issue(UserEntity user, List<TokenRevocation> revocations);

    /**
     * Validates a refresh token and extracts its account identifier without consuming it.
     *
     * @param token the signed refresh token
     * @return the authenticated account identifier
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} for invalid credentials
     */
    UUID refreshUserId(String token);

    /**
     * Prepares revocation of an access or refresh token after validating its signature and claims.
     *
     * @param token the signed token
     * @param type the expected ACCESS or REFRESH type
     * @return the revocation, or an empty list for an expired access token
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} for invalid credentials
     */
    List<TokenRevocation> revocation(String token, String type);

    /**
     * Revokes the session's supplied tokens without deleting account-wide auth state.
     *
     * @param accessToken the access token, which may already be expired
     * @param refreshToken the unexpired refresh token
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} for invalid or revoked tokens,
     *         or {@link ErrorCode#AUTH_STATE_UNAVAILABLE} when Redis is unavailable
     */
    void logout(String accessToken, String refreshToken);
}
