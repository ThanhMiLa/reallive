package com.reallive.userservice.auth.service;

import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.request.ChangePasswordRequest;
import com.reallive.userservice.auth.dto.request.ForgotPasswordRequest;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.dto.request.ResetPasswordRequest;
import com.reallive.userservice.auth.dto.request.VerifyResetOtpRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.dto.response.ForgotPasswordResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;

import java.util.UUID;

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

    /**
     * Changes the authenticated user's password, revokes the current access and refresh tokens,
     * and issues a fresh token pair.
     *
     * <p>Verifies the current password, ensures the new password differs from the current password,
     * encodes the new password, and updates the user record. Revocation invalidates the supplied
     * access token and refresh token in Redis for their remaining lifetimes.</p>
     *
     * @param userId the authenticated user ID
     * @param accessToken the current access token to revoke; must not be {@code null} or blank
     * @param refreshToken the current refresh token to revoke (may be {@code null} or blank if absent)
     * @param request the password change request containing current, new, and confirmation passwords
     * @return the newly issued access and refresh tokens
     * @throws AppException with {@link ErrorCode#USER_NOT_FOUND} if the user does not exist,
     *                      {@link ErrorCode#USER_NOT_ACTIVE} if the user is inactive,
     *                      {@link ErrorCode#PASSWORD_NOT_SET} if the user account has no local password,
     *                      {@link ErrorCode#PASSWORD_NOT_MATCH} if the new password and confirmation differ,
     *                      {@link ErrorCode#CURRENT_PASSWORD_INCORRECT} if the current password does not match,
     *                      {@link ErrorCode#NEW_PASSWORD_SAME_AS_CURRENT} if the new password matches the current password, or
     *                      {@link ErrorCode#UNAUTHENTICATED} if the tokens cannot be parsed or are already revoked
     */
    AuthResponse changePassword(UUID userId, String accessToken, String refreshToken, ChangePasswordRequest request);

    /**
     * Initiates the password reset process by generating and sending a one-time password (OTP) to the user's email.
     *
     * <p>Checks that the user exists and is active, enforces the reset OTP cooldown period,
     * generates a 6-digit OTP, stores its cryptographic hash in Redis, and publishes an email
     * event to Kafka for asynchronous delivery.</p>
     *
     * @param request the forgot password request containing the user's email
     * @return the forgot password response containing OTP expiration details
     * @throws AppException with {@link ErrorCode#USER_NOT_FOUND} if no user with the email exists,
     *                      {@link ErrorCode#USER_NOT_ACTIVE} if the user account is inactive or banned,
     *                      {@link ErrorCode#OTP_COOLDOWN} if a reset OTP was recently requested, or
     *                      {@link ErrorCode#OTP_REQUEST_UNAVAILABLE} if Redis or Kafka is unavailable
     */
    ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request);

    /**
     * Verifies the submitted password reset OTP, invalidates it in Redis, and issues a temporary reset token.
     *
     * <p>Consumes the OTP atomically from Redis to prevent replay attacks, then generates a
     * cryptographically secure reset token associated with the user and persists it with a TTL.</p>
     *
     * @param request the OTP verification request containing email and OTP
     * @return the issued single-use reset token string
     * @throws AppException with {@link ErrorCode#USER_NOT_FOUND} if the user does not exist,
     *                      {@link ErrorCode#USER_NOT_ACTIVE} if the user is inactive or banned,
     *                      {@link ErrorCode#OTP_NOT_FOUND} if the OTP has expired or was not requested,
     *                      {@link ErrorCode#OTP_MISMATCH} if the OTP is incorrect, or
     *                      {@link ErrorCode#OTP_VERIFICATION_UNAVAILABLE} if Redis is unavailable
     */
    String verifyResetOtp(VerifyResetOtpRequest request);

    /**
     * Resets the user's password using a valid, unconsumed password reset token.
     *
     * <p>Validates and consumes the reset token from Redis to ensure single-use, verifies the new
     * password against the confirmation, hashes the new password, and persists it to the database.</p>
     *
     * @param resetToken the single-use reset token supplied from the HttpOnly cookie
     * @param request the reset password request containing new password and confirmation
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} if the reset token is missing, invalid, or expired,
     *                      {@link ErrorCode#USER_NOT_FOUND} if the associated user no longer exists,
     *                      {@link ErrorCode#USER_NOT_ACTIVE} if the user is inactive or banned,
     *                      {@link ErrorCode#PASSWORD_NOT_MATCH} if the new password and confirmation differ, or
     *                      {@link ErrorCode#NEW_PASSWORD_SAME_AS_CURRENT} if the new password matches the current password
     */
    void resetPassword(String resetToken, ResetPasswordRequest request);
}
