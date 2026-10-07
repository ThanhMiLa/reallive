package com.reallive.userservice.auth.controller;

import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.service.AuthService;
import com.reallive.userservice.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthController {
    AuthService authService;

    @NonFinal
    @Value("${auth.cookie.access-token.name}")
    String accessTokenName;

    @NonFinal
    @Value("${auth.cookie.access-token.max-age}")
    long accessTokenMaxAge;

    @NonFinal
    @Value("${auth.cookie.access-token.secure}")
    boolean isCookieAccessTokenSecure;

    @NonFinal
    @Value("${auth.cookie.access-token.http-only}")
    boolean accessTokenHttpOnly;

    @NonFinal
    @Value("${auth.cookie.access-token.same-site}")
    String accessTokenSameSite;

    @NonFinal
    @Value("${auth.cookie.access-token.path}")
    String accessTokenPath;

    @NonFinal
    @Value("${auth.cookie.refresh-token.name}")
    String refreshTokenName;

    @NonFinal
    @Value("${auth.cookie.refresh-token.max-age}")
    long refreshTokenMaxAge;

    @NonFinal
    @Value("${auth.cookie.refresh-token.secure}")
    boolean isCookieRefreshTokenSecure;

    @NonFinal
    @Value("${auth.cookie.refresh-token.http-only}")
    boolean refreshTokenHttpOnly;

    @NonFinal
    @Value("${auth.cookie.refresh-token.same-site}")
    String refreshTokenSameSite;

    @NonFinal
    @Value("${auth.cookie.refresh-token.path}")
    String refreshTokenPath;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@RequestBody @Valid AuthRequest authRequest, HttpServletResponse response) {
        AuthResponse result = authService.login(authRequest);
        addAuthCookies(response, result);
        return ResponseEntity.ok(ApiResponse.<AuthResponse>builder()
                .status(200)
                .code(1000)
                .message("Login successful")
                .result(result)
                .build());
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @RequestBody @Valid RegisterRequest registerRequest, HttpServletResponse response){

        AuthResponse result = authService.register(registerRequest);
        addAuthCookies(response, result);

        return ResponseEntity.ok(ApiResponse.<AuthResponse>builder()
                .status(200)
                .code(1000)
                .message("Register successful")
                .result(result)
                .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "${auth.cookie.access-token.name}", required = false) String accessToken,
            @CookieValue(name = "${auth.cookie.refresh-token.name}", required = false) String refreshToken,
            HttpServletResponse response){

        authService.logout(accessToken, refreshToken);
        clearAuthCookies(response);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .status(200)
                .code(1000)
                .message("Logout successfully")
                .result(null)
                .build());
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = "${auth.cookie.refresh-token.name}", required = false) String refreshToken,
            HttpServletResponse response) {

        var result = authService.refresh(refreshToken);
        addAuthCookies(response, result);

        return ResponseEntity.ok(ApiResponse.<AuthResponse>builder()
                .status(200)
                .code(1000)
                .message("Refresh token successful")
                .result(result)
                .build());
    }

    // ==================== Private Helper Methods ====================

    /**
     * Sets access token and refresh token as HttpOnly cookies on the response,
     * then clears the token values from the response body for security.
     */
    private void addAuthCookies(HttpServletResponse response, AuthResponse result) {
        ResponseCookie accessTokenCookie = buildCookie(
                accessTokenName, result.getAccessToken(),
                accessTokenHttpOnly, isCookieAccessTokenSecure,
                accessTokenPath, accessTokenMaxAge, accessTokenSameSite);

        ResponseCookie refreshTokenCookie = buildCookie(
                refreshTokenName, result.getRefreshToken(),
                refreshTokenHttpOnly, isCookieRefreshTokenSecure,
                refreshTokenPath, refreshTokenMaxAge, refreshTokenSameSite);

        response.addHeader(HttpHeaders.SET_COOKIE, accessTokenCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString());

        // Clear tokens from response body — they are now in cookies only
        result.setAccessToken(null);
        result.setRefreshToken(null);
    }

    /**
     * Clears access token and refresh token cookies by setting maxAge to 0.
     */
    private void clearAuthCookies(HttpServletResponse response) {
        ResponseCookie expiredAccessToken = buildCookie(
                accessTokenName, "",
                accessTokenHttpOnly, isCookieAccessTokenSecure,
                accessTokenPath, 0, accessTokenSameSite);

        ResponseCookie expiredRefreshToken = buildCookie(
                refreshTokenName, "",
                refreshTokenHttpOnly, isCookieRefreshTokenSecure,
                refreshTokenPath, 0, refreshTokenSameSite);

        response.addHeader(HttpHeaders.SET_COOKIE, expiredAccessToken.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, expiredRefreshToken.toString());
    }

    private ResponseCookie buildCookie(String name, String value,
                                       boolean httpOnly, boolean secure,
                                       String path, long maxAge, String sameSite) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .path(path)
                .maxAge(maxAge)
                .sameSite(sameSite)
                .build();
    }
}
