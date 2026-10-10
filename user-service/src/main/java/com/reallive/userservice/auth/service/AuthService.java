package com.reallive.userservice.auth.service;

import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;

public interface AuthService {

    /**
     * Authenticates an active user by username and password and issues access and refresh tokens.
     *
     * @param authRequest the username and password to authenticate
     * @return the newly issued access and refresh tokens
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} if the credentials are incorrect
     *                      or the user is inactive
     */
    AuthResponse login(AuthRequest authRequest);

    /**
     * Creates a user with the {@code USER} role and a profile, then issues authentication tokens.
     *
     * <p>The user and profile are persisted in one transaction. The initial display name is
     * the username.</p>
     *
     * @param registerRequest the username, email, password and password confirmation for the new user
     * @return the newly issued access and refresh tokens for the registered user
     * @throws AppException with {@link ErrorCode#USERNAME_ALREADY_EXISTS} or
     *                      {@link ErrorCode#EMAIL_ALREADY_EXISTS} if either value is already in use,
     *                      {@link ErrorCode#PASSWORD_NOT_MATCH} if the passwords differ, or
     *                      {@link ErrorCode#USER_NOT_FOUND} if the default role is unavailable
     */
    AuthResponse register(RegisterRequest registerRequest);

    /**
     * Revokes the supplied access and refresh tokens for their remaining lifetimes.
     *
     * <p>An expired access token is ignored, but an expired refresh token is rejected.
     * Revocation is not atomic: the access token may remain revoked if processing the refresh
     * token fails. Reusing an already revoked, unexpired token is rejected.</p>
     *
     * @param accessToken the access token to revoke; must not be {@code null} or blank
     * @param refreshToken the refresh token to revoke; must not be {@code null} or blank
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} if either token is missing,
     *                      cannot be parsed or is already revoked, or if the refresh token has expired
     */
    void logout(String accessToken, String refreshToken);

    /**
     * Consumes a refresh token and issues new access and refresh tokens for its active user.
     *
     * <p>The supplied refresh token is revoked before the user is checked and remains revoked
     * even if issuing replacement tokens fails. The previous access token is not revoked.</p>
     *
     * @param token the refresh token to consume; must not be {@code null} or blank
     * @return the newly issued access and refresh tokens
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} if the token is missing,
     *                      cannot be parsed, has expired or is already revoked,
     *                      {@link ErrorCode#USER_NOT_FOUND} if its user no longer exists, or
     *                      {@link ErrorCode#USER_NOT_ACTIVE} if its user is inactive
     */
    AuthResponse refresh(String token);
}
