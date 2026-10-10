package com.reallive.userservice.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.request.ChangePasswordRequest;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.service.AuthService;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.exception.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<AnnotationConfigWebApplicationContext> contexts = new ArrayList<>();
    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService);

        ReflectionTestUtils.setField(controller, "accessTokenName", "access_token");
        ReflectionTestUtils.setField(controller, "accessTokenMaxAge", 900L);
        ReflectionTestUtils.setField(controller, "isCookieAccessTokenSecure", false);
        ReflectionTestUtils.setField(controller, "accessTokenHttpOnly", true);
        ReflectionTestUtils.setField(controller, "accessTokenSameSite", "Lax");
        ReflectionTestUtils.setField(controller, "accessTokenPath", "/");

        ReflectionTestUtils.setField(controller, "refreshTokenName", "refresh_token");
        ReflectionTestUtils.setField(controller, "refreshTokenMaxAge", 2592000L);
        ReflectionTestUtils.setField(controller, "isCookieRefreshTokenSecure", false);
        ReflectionTestUtils.setField(controller, "refreshTokenHttpOnly", true);
        ReflectionTestUtils.setField(controller, "refreshTokenSameSite", "Lax");
        ReflectionTestUtils.setField(controller, "refreshTokenPath", "/api/auth");

        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        contexts.add(context);
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.addBeanFactoryPostProcessor(beanFactory -> {
            beanFactory.registerSingleton("authController", controller);
            beanFactory.registerSingleton("exceptionHandler", new GlobalExceptionHandler());
            beanFactory.addEmbeddedValueResolver(value -> value
                    .replace("${auth.cookie.access-token.name}", "access_token")
                    .replace("${auth.cookie.refresh-token.name}", "refresh_token"));
        });
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        contexts.forEach(AnnotationConfigWebApplicationContext::close);
    }

    // =========================================================================
    // 1. LOGIN CONTROLLER TESTS
    // =========================================================================
    @Nested
    @DisplayName("POST /api/auth/login")
    class LoginEndpointTests {

        @Test
        @DisplayName("Returns 400 Bad Request when username is blank or less than 5 characters")
        void login_ReturnsBadRequest_WhenUsernameInvalid() throws Exception {
            AuthRequest invalidRequest = AuthRequest.builder()
                    .username("abc") // < 5 chars
                    .password("validpass123")
                    .build();

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.USERNAME_INVALID.getCode()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.USERNAME_INVALID.getMessage()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when password is blank or less than 5 characters")
        void login_ReturnsBadRequest_WhenPasswordInvalid() throws Exception {
            AuthRequest invalidRequest = AuthRequest.builder()
                    .username("validuser")
                    .password("12") // < 5 chars
                    .build();

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_INVALID.getCode()));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized when service throws UNAUTHENTICATED")
        void login_ReturnsUnauthorized_WhenServiceThrowsUnauthenticated() throws Exception {
            AuthRequest request = AuthRequest.builder()
                    .username("validuser")
                    .password("wrongpassword")
                    .build();

            when(authService.login(any(AuthRequest.class)))
                    .thenThrow(new AppException(ErrorCode.UNAUTHENTICATED));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
        }

        @Test
        @DisplayName("Returns 200 OK, sets auth cookies, and clears tokens from JSON body on success")
        void login_ReturnsOk_AndSetsCookies_OnSuccess() throws Exception {
            AuthRequest request = AuthRequest.builder()
                    .username("validuser")
                    .password("validpass123")
                    .build();

            AuthResponse serviceResponse = AuthResponse.builder()
                    .accessToken("mock-access-token")
                    .refreshToken("mock-refresh-token")
                    .build();

            when(authService.login(any(AuthRequest.class))).thenReturn(serviceResponse);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.code").value(1000))
                    .andExpect(jsonPath("$.message").value("Login successful"))
                    .andExpect(jsonPath("$.result.accessToken").value(nullValue()))
                    .andExpect(jsonPath("$.result.refreshToken").value(nullValue()))
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.hasItem(containsString("access_token=mock-access-token"))))
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.hasItem(containsString("refresh_token=mock-refresh-token"))));
        }
    }

    // =========================================================================
    // 2. REGISTER CONTROLLER TESTS
    // =========================================================================
    @Nested
    @DisplayName("POST /api/auth/register")
    class RegisterEndpointTests {

        @Test
        @DisplayName("Returns 400 Bad Request when username is invalid")
        void register_ReturnsBadRequest_WhenUsernameInvalid() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .username("usr")
                    .password("pass123")
                    .confirmPassword("pass123")
                    .email("user@example.com")
                    .build();

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.USERNAME_INVALID.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when password is invalid")
        void register_ReturnsBadRequest_WhenPasswordInvalid() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .username("validuser")
                    .password("12")
                    .confirmPassword("validpass123")
                    .email("user@example.com")
                    .build();

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_INVALID.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when confirmPassword is invalid")
        void register_ReturnsBadRequest_WhenConfirmPasswordInvalid() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .username("validuser")
                    .password("validpass123")
                    .confirmPassword("12")
                    .email("user@example.com")
                    .build();

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_CONFIRM_INVALID.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when email format is invalid")
        void register_ReturnsBadRequest_WhenEmailInvalid() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .username("validuser")
                    .password("validpass123")
                    .confirmPassword("validpass123")
                    .email("invalid-email-format")
                    .build();

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_INVALID.getCode()));
        }

        @Test
        @DisplayName("Returns 409 Conflict when username already exists")
        void register_ReturnsConflict_WhenUsernameAlreadyExists() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .username("existinguser")
                    .password("validpass123")
                    .confirmPassword("validpass123")
                    .email("user@example.com")
                    .build();

            when(authService.register(any(RegisterRequest.class)))
                    .thenThrow(new AppException(ErrorCode.USERNAME_ALREADY_EXISTS));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.USERNAME_ALREADY_EXISTS.getCode()));
        }

        @Test
        @DisplayName("Returns 409 Conflict when email already exists")
        void register_ReturnsConflict_WhenEmailAlreadyExists() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .username("validuser")
                    .password("validpass123")
                    .confirmPassword("validpass123")
                    .email("existing@example.com")
                    .build();

            when(authService.register(any(RegisterRequest.class)))
                    .thenThrow(new AppException(ErrorCode.EMAIL_ALREADY_EXISTS));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_ALREADY_EXISTS.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when passwords do not match")
        void register_ReturnsBadRequest_WhenPasswordsDoNotMatch() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .username("validuser")
                    .password("validpass123")
                    .confirmPassword("differentpass123")
                    .email("user@example.com")
                    .build();

            when(authService.register(any(RegisterRequest.class)))
                    .thenThrow(new AppException(ErrorCode.PASSWORD_NOT_MATCH));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_NOT_MATCH.getCode()));
        }

        @Test
        @DisplayName("Returns 200 OK and sets cookies on successful registration")
        void register_ReturnsOk_OnSuccess() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .username("validuser")
                    .password("validpass123")
                    .confirmPassword("validpass123")
                    .email("user@example.com")
                    .build();

            AuthResponse serviceResponse = AuthResponse.builder()
                    .accessToken("registered-access-token")
                    .refreshToken("registered-refresh-token")
                    .build();

            when(authService.register(any(RegisterRequest.class))).thenReturn(serviceResponse);

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Register successful"))
                    .andExpect(jsonPath("$.result.accessToken").value(nullValue()))
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.hasItem(containsString("access_token=registered-access-token"))));
        }
    }

    // =========================================================================
    // 3. LOGOUT CONTROLLER TESTS
    // =========================================================================
    @Nested
    @DisplayName("POST /api/auth/logout")
    class LogoutEndpointTests {

        @Test
        @DisplayName("Returns 401 Unauthorized when logout cookies are missing")
        void logout_ReturnsUnauthorized_WhenNoCookiesProvided() throws Exception {
            doThrow(new AppException(ErrorCode.UNAUTHENTICATED))
                    .when(authService).logout(null, null);

            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

            verify(authService).logout(null, null);
        }

        @Test
        @DisplayName("Returns 200 OK and clears cookies when access and refresh cookies are provided")
        void logout_ReturnsOk_WhenCookiesProvided() throws Exception {
            mockMvc.perform(post("/api/auth/logout")
                            .cookie(new Cookie("access_token", "jwt-access-token"))
                            .cookie(new Cookie("refresh_token", "jwt-refresh-token")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Logout successfully"))
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.hasItem(containsString("Max-Age=0"))));

            verify(authService).logout("jwt-access-token", "jwt-refresh-token");
        }

        @Test
        @DisplayName("Returns 401 Unauthorized when authService throws UNAUTHENTICATED")
        void logout_ReturnsUnauthorized_WhenServiceThrows() throws Exception {
            doThrow(new AppException(ErrorCode.UNAUTHENTICATED))
                    .when(authService).logout(any(), any());

            mockMvc.perform(post("/api/auth/logout")
                            .cookie(new Cookie("access_token", "bad-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
        }
    }

    // =========================================================================
    // 4. REFRESH CONTROLLER TESTS
    // =========================================================================
    @Nested
    @DisplayName("POST /api/auth/refresh")
    class RefreshEndpointTests {

        @Test
        @DisplayName("Returns 401 Unauthorized when refresh cookie is missing")
        void refresh_ReturnsUnauthorized_WhenCookieMissing() throws Exception {
            when(authService.refresh(null))
                    .thenThrow(new AppException(ErrorCode.UNAUTHENTICATED));

            mockMvc.perform(post("/api/auth/refresh"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized when refresh token is invalid or revoked")
        void refresh_ReturnsUnauthorized_WhenTokenRevoked() throws Exception {
            when(authService.refresh("revoked-token"))
                    .thenThrow(new AppException(ErrorCode.UNAUTHENTICATED));

            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie("refresh_token", "revoked-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
        }

        @Test
        @DisplayName("Returns 404 Not Found when user from token subject does not exist")
        void refresh_ReturnsNotFound_WhenUserDoesNotExist() throws Exception {
            when(authService.refresh("valid-token-deleted-user"))
                    .thenThrow(new AppException(ErrorCode.USER_NOT_FOUND));

            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie("refresh_token", "valid-token-deleted-user")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("Returns 200 OK and sets fresh cookies on successful refresh")
        void refresh_ReturnsOk_OnSuccess() throws Exception {
            AuthResponse newTokens = AuthResponse.builder()
                    .accessToken("new-access-token")
                    .refreshToken("new-refresh-token")
                    .build();

            when(authService.refresh("valid-refresh-token")).thenReturn(newTokens);

            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie("refresh_token", "valid-refresh-token")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Refresh token successful"))
                    .andExpect(jsonPath("$.result.accessToken").value(nullValue()))
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.hasItem(containsString("access_token=new-access-token"))))
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.hasItem(containsString("refresh_token=new-refresh-token"))));
        }
    }

    // =========================================================================
    // 5. CHANGE PASSWORD CONTROLLER TESTS
    // =========================================================================
    @Nested
    @DisplayName("POST /api/auth/change-password")
    class ChangePasswordEndpointTests {

        private Authentication createAuthentication(UUID userId) {
            Jwt jwt = Jwt.withTokenValue("jwt-access-token")
                    .header("alg", "HS512")
                    .subject(userId.toString())
                    .claim("scope", "ROLE_USER")
                    .build();
            return new JwtAuthenticationToken(jwt);
        }

        private void authenticate(UUID userId) {
            SecurityContextHolder.getContext().setAuthentication(createAuthentication(userId));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized when user is not authenticated")
        void changePassword_ReturnsUnauthorized_WhenNoAuthentication() throws Exception {
            SecurityContextHolder.clearContext();
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when currentPassword is invalid (< 5 chars)")
        void changePassword_ReturnsBadRequest_WhenCurrentPasswordInvalid() throws Exception {
            UUID userId = UUID.randomUUID();
            authenticate(userId);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("12")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            mockMvc.perform(post("/api/auth/change-password")
                            .principal(createAuthentication(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.CURRENT_PASSWORD_INVALID.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when newPassword is invalid (< 5 chars)")
        void changePassword_ReturnsBadRequest_WhenNewPasswordInvalid() throws Exception {
            UUID userId = UUID.randomUUID();
            authenticate(userId);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("12")
                    .confirmPassword("newSecret123")
                    .build();

            mockMvc.perform(post("/api/auth/change-password")
                            .principal(createAuthentication(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_INVALID.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when confirmPassword is invalid (< 5 chars)")
        void changePassword_ReturnsBadRequest_WhenConfirmPasswordInvalid() throws Exception {
            UUID userId = UUID.randomUUID();
            authenticate(userId);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("12")
                    .build();

            mockMvc.perform(post("/api/auth/change-password")
                            .principal(createAuthentication(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_CONFIRM_INVALID.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when service throws CURRENT_PASSWORD_INCORRECT")
        void changePassword_ReturnsBadRequest_WhenCurrentPasswordIncorrect() throws Exception {
            UUID userId = UUID.randomUUID();
            authenticate(userId);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("wrongOldSecret")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(authService.changePassword(eq(userId), any(), any(), any(ChangePasswordRequest.class)))
                    .thenThrow(new AppException(ErrorCode.CURRENT_PASSWORD_INCORRECT));

            mockMvc.perform(post("/api/auth/change-password")
                            .principal(createAuthentication(userId))
                            .cookie(new Cookie("access_token", "jwt-access-token"))
                            .cookie(new Cookie("refresh_token", "jwt-refresh-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.CURRENT_PASSWORD_INCORRECT.getCode()));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when service throws NEW_PASSWORD_SAME_AS_CURRENT")
        void changePassword_ReturnsBadRequest_WhenNewPasswordSameAsCurrent() throws Exception {
            UUID userId = UUID.randomUUID();
            authenticate(userId);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("oldSecret123")
                    .confirmPassword("oldSecret123")
                    .build();

            when(authService.changePassword(eq(userId), any(), any(), any(ChangePasswordRequest.class)))
                    .thenThrow(new AppException(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT));

            mockMvc.perform(post("/api/auth/change-password")
                            .principal(createAuthentication(userId))
                            .cookie(new Cookie("access_token", "jwt-access-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT.getCode()));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized when service throws UNAUTHENTICATED due to missing refresh token")
        void changePassword_ReturnsUnauthorized_WhenRefreshTokenMissing() throws Exception {
            UUID userId = UUID.randomUUID();
            authenticate(userId);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(authService.changePassword(eq(userId), eq("jwt-access-token"), isNull(), any(ChangePasswordRequest.class)))
                    .thenThrow(new AppException(ErrorCode.UNAUTHENTICATED));

            mockMvc.perform(post("/api/auth/change-password")
                            .principal(createAuthentication(userId))
                            .cookie(new Cookie("access_token", "jwt-access-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
        }

        @Test
        @DisplayName("Returns 200 OK, sets fresh auth cookies, and clears tokens from JSON body on success")
        void changePassword_ReturnsOk_AndSetsCookies_OnSuccess() throws Exception {
            UUID userId = UUID.randomUUID();
            authenticate(userId);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            AuthResponse serviceResponse = AuthResponse.builder()
                    .accessToken("fresh-access-token")
                    .refreshToken("fresh-refresh-token")
                    .build();

            when(authService.changePassword(eq(userId), eq("jwt-access-token"), eq("jwt-refresh-token"), any(ChangePasswordRequest.class)))
                    .thenReturn(serviceResponse);

            mockMvc.perform(post("/api/auth/change-password")
                            .principal(createAuthentication(userId))
                            .cookie(new Cookie("access_token", "jwt-access-token"))
                            .cookie(new Cookie("refresh_token", "jwt-refresh-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.code").value(1000))
                    .andExpect(jsonPath("$.message").value("Password changed successfully"))
                    .andExpect(jsonPath("$.result.accessToken").value(nullValue()))
                    .andExpect(jsonPath("$.result.refreshToken").value(nullValue()))
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.hasItem(containsString("access_token=fresh-access-token"))))
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.hasItem(containsString("refresh_token=fresh-refresh-token"))));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration implements WebMvcConfigurer {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new AuthenticationPrincipalArgumentResolver());
        }
    }
}
