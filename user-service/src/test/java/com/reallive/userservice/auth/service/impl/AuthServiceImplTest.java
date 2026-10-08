package com.reallive.userservice.auth.service.impl;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.entity.PermissionEntity;
import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private static final String SIGNING_KEY = "0123456789012345678901234567890123456789012345678901234567890123";

    private UserRepository userRepository;
    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private RoleRepository roleRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private AuthServiceImpl authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        roleRepository = mock(RoleRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        authService = new AuthServiceImpl(
                userRepository,
                userMapper,
                passwordEncoder,
                roleRepository,
                redisTemplate
        );

        ReflectionTestUtils.setField(authService, "SIGNER_KEY", SIGNING_KEY);
        ReflectionTestUtils.setField(authService, "TTL_ACCESS_TOKEN", 900L);
        ReflectionTestUtils.setField(authService, "TTL_REFRESH_TOKEN", 2592000L);
    }

    // =========================================================================
    // 1. LOGIN TESTS
    // =========================================================================
    @Nested
    @DisplayName("Login Branch Coverage")
    class LoginTests {

        @Test
        @DisplayName("Throws UNAUTHENTICATED when username does not exist")
        void login_ThrowsUnauthenticated_WhenUserNotFound() {
            AuthRequest request = AuthRequest.builder()
                    .username("unknown_user")
                    .password("secret123")
                    .build();

            when(userRepository.findByUsername("unknown_user")).thenReturn(Optional.empty());

            AppException exception = assertThrows(AppException.class, () -> authService.login(request));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when password is wrong")
        void login_ThrowsUnauthenticated_WhenPasswordDoesNotMatch() {
            AuthRequest request = AuthRequest.builder()
                    .username("john_doe")
                    .password("wrong_password")
                    .build();

            UserEntity user = createUser("john_doe", "hashed_pwd", UserStatus.ACTIVE);
            when(userRepository.findByUsername("john_doe")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong_password", "hashed_pwd")).thenReturn(false);

            AppException exception = assertThrows(AppException.class, () -> authService.login(request));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when user account is BANNED/INACTIVE")
        void login_ThrowsUnauthenticated_WhenUserInactive() {
            AuthRequest request = AuthRequest.builder()
                    .username("banned_user")
                    .password("secret123")
                    .build();

            UserEntity user = createUser("banned_user", "hashed_pwd", UserStatus.BANNED);
            when(userRepository.findByUsername("banned_user")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("secret123", "hashed_pwd")).thenReturn(true);

            AppException exception = assertThrows(AppException.class, () -> authService.login(request));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }

        @Test
        @DisplayName("Successfully logs in and generates tokens with roles and permissions")
        void login_Success_WithRolesAndPermissions() throws Exception {
            AuthRequest request = AuthRequest.builder()
                    .username("active_user")
                    .password("secret123")
                    .build();

            UserEntity user = createUser("active_user", "hashed_pwd", UserStatus.ACTIVE);

            PermissionEntity permission = new PermissionEntity();
            permission.setId(UUID.randomUUID());
            permission.setName("USER_READ");

            RoleEntity role = new RoleEntity();
            role.setId(UUID.randomUUID());
            role.setName("ADMIN");
            role.setPermissions(Set.of(permission));

            user.setRoles(Set.of(role));

            when(userRepository.findByUsername("active_user")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("secret123", "hashed_pwd")).thenReturn(true);

            AuthResponse response = authService.login(request);

            assertNotNull(response);
            assertNotNull(response.getAccessToken());
            assertNotNull(response.getRefreshToken());

            SignedJWT accessToken = SignedJWT.parse(response.getAccessToken());
            assertEquals(user.getId().toString(), accessToken.getJWTClaimsSet().getSubject());
            assertEquals("reallive.com", accessToken.getJWTClaimsSet().getIssuer());
            assertEquals("ACCESS", accessToken.getJWTClaimsSet().getStringClaim("type"));
            assertTrue(accessToken.getJWTClaimsSet().getStringClaim("scope").contains("ROLE_ADMIN"));
            assertTrue(accessToken.getJWTClaimsSet().getStringClaim("scope").contains("USER_READ"));
        }

        @Test
        @DisplayName("Successfully logs in when user has no roles assigned")
        void login_Success_WhenUserHasNoRoles() throws Exception {
            AuthRequest request = AuthRequest.builder()
                    .username("user_no_roles")
                    .password("secret123")
                    .build();

            UserEntity user = createUser("user_no_roles", "hashed_pwd", UserStatus.ACTIVE);
            user.setRoles(new HashSet<>());

            when(userRepository.findByUsername("user_no_roles")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("secret123", "hashed_pwd")).thenReturn(true);

            AuthResponse response = authService.login(request);

            assertNotNull(response);
            SignedJWT accessToken = SignedJWT.parse(response.getAccessToken());
            assertEquals("", accessToken.getJWTClaimsSet().getStringClaim("scope"));
        }
    }

    // =========================================================================
    // 2. REGISTER TESTS
    // =========================================================================
    @Nested
    @DisplayName("Register Branch Coverage")
    class RegisterTests {

        @Test
        @DisplayName("Throws USERNAME_ALREADY_EXISTS when username is taken")
        void register_ThrowsUsernameAlreadyExists() {
            RegisterRequest request = RegisterRequest.builder()
                    .username("existing_user")
                    .email("new@example.com")
                    .password("password123")
                    .confirmPassword("password123")
                    .build();

            when(userRepository.existsByUsername("existing_user")).thenReturn(true);

            AppException exception = assertThrows(AppException.class, () -> authService.register(request));
            assertEquals(ErrorCode.USERNAME_ALREADY_EXISTS, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws EMAIL_ALREADY_EXISTS when email is taken")
        void register_ThrowsEmailAlreadyExists() {
            RegisterRequest request = RegisterRequest.builder()
                    .username("new_user")
                    .email("existing@example.com")
                    .password("password123")
                    .confirmPassword("password123")
                    .build();

            when(userRepository.existsByUsername("new_user")).thenReturn(false);
            when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

            AppException exception = assertThrows(AppException.class, () -> authService.register(request));
            assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws PASSWORD_NOT_MATCH when password and confirmPassword do not match")
        void register_ThrowsPasswordNotMatch() {
            RegisterRequest request = RegisterRequest.builder()
                    .username("new_user")
                    .email("new@example.com")
                    .password("password123")
                    .confirmPassword("different_password")
                    .build();

            when(userRepository.existsByUsername("new_user")).thenReturn(false);
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

            AppException exception = assertThrows(AppException.class, () -> authService.register(request));
            assertEquals(ErrorCode.PASSWORD_NOT_MATCH, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws USER_NOT_FOUND when default USER role is missing from database")
        void register_ThrowsUserNotFound_WhenDefaultRoleMissing() {
            RegisterRequest request = RegisterRequest.builder()
                    .username("new_user")
                    .email("new@example.com")
                    .password("password123")
                    .confirmPassword("password123")
                    .build();

            when(userRepository.existsByUsername("new_user")).thenReturn(false);
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
            when(userMapper.toUserEntity(request)).thenReturn(new UserEntity());
            when(passwordEncoder.encode("password123")).thenReturn("encoded_pwd");
            when(roleRepository.findByName("USER")).thenReturn(Optional.empty());

            AppException exception = assertThrows(AppException.class, () -> authService.register(request));
            assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        }

        @Test
        @DisplayName("Successfully registers user and automatically logs in")
        void register_Success() {
            RegisterRequest request = RegisterRequest.builder()
                    .username("new_user")
                    .email("new@example.com")
                    .password("password123")
                    .confirmPassword("password123")
                    .build();

            UserEntity mappedUser = new UserEntity();
            mappedUser.setUsername("new_user");

            RoleEntity roleUser = new RoleEntity();
            roleUser.setId(UUID.randomUUID());
            roleUser.setName("USER");
            roleUser.setPermissions(new HashSet<>());

            UserEntity savedUser = createUser("new_user", "encoded_pwd", UserStatus.ACTIVE);
            savedUser.setRoles(Set.of(roleUser));

            when(userRepository.existsByUsername("new_user")).thenReturn(false);
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
            when(userMapper.toUserEntity(request)).thenReturn(mappedUser);
            when(passwordEncoder.encode("password123")).thenReturn("encoded_pwd");
            when(roleRepository.findByName("USER")).thenReturn(Optional.of(roleUser));
            when(userRepository.save(mappedUser)).thenReturn(savedUser);

            // Stubs for automatic login call
            when(userRepository.findByUsername("new_user")).thenReturn(Optional.of(savedUser));
            when(passwordEncoder.matches("password123", "encoded_pwd")).thenReturn(true);

            AuthResponse response = authService.register(request);

            assertNotNull(response);
            assertNotNull(response.getAccessToken());
            assertNotNull(response.getRefreshToken());
            verify(userRepository).save(mappedUser);
        }
    }

    // =========================================================================
    // 3. LOGOUT TESTS
    // =========================================================================
    @Nested
    @DisplayName("Logout Branch Coverage")
    class LogoutTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("No Redis interaction when both tokens are null or blank")
        void logout_DoesNothing_WhenTokensNullOrBlank(String token) {
            authService.logout(token, token);
            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Revokes only access token when refresh token is null")
        void logout_RevokesAccessTokenOnly() throws Exception {
            String jti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", "user-123", jti, Instant.now().plusSeconds(300));

            when(valueOperations.setIfAbsent(eq("auth:revoked:access:" + jti), eq("1"), any(Duration.class)))
                    .thenReturn(true);

            authService.logout(accessToken, null);

            verify(valueOperations).setIfAbsent(eq("auth:revoked:access:" + jti), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("Revokes only refresh token when access token is null")
        void logout_RevokesRefreshTokenOnly() throws Exception {
            String jti = UUID.randomUUID().toString();
            String refreshToken = createSignedToken("REFRESH", "user-123", jti, Instant.now().plusSeconds(3600));

            when(valueOperations.setIfAbsent(eq("auth:revoked:refresh:" + jti), eq("1"), any(Duration.class)))
                    .thenReturn(true);

            authService.logout(null, refreshToken);

            verify(valueOperations).setIfAbsent(eq("auth:revoked:refresh:" + jti), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("Revokes both access and refresh tokens when both are valid")
        void logout_RevokesBothTokens() throws Exception {
            String accessJti = UUID.randomUUID().toString();
            String refreshJti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", "user-123", accessJti, Instant.now().plusSeconds(300));
            String refreshToken = createSignedToken("REFRESH", "user-123", refreshJti, Instant.now().plusSeconds(3600));

            when(valueOperations.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(true);

            authService.logout(accessToken, refreshToken);

            verify(valueOperations).setIfAbsent(eq("auth:revoked:access:" + accessJti), eq("1"), any(Duration.class));
            verify(valueOperations).setIfAbsent(eq("auth:revoked:refresh:" + refreshJti), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("Ignores expired access token during logout without calling Redis")
        void logout_IgnoresExpiredAccessToken() throws Exception {
            String accessToken = createSignedToken("ACCESS", "user-123", UUID.randomUUID().toString(),
                    Instant.now().minusSeconds(60));

            authService.logout(accessToken, null);

            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when token was already revoked in Redis")
        void logout_ThrowsUnauthenticated_WhenTokenAlreadyRevoked() throws Exception {
            String jti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", "user-123", jti, Instant.now().plusSeconds(300));

            when(valueOperations.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(false);

            AppException exception = assertThrows(AppException.class, () -> authService.logout(accessToken, null));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when token format is malformed")
        void logout_ThrowsUnauthenticated_WhenTokenMalformed() {
            AppException exception = assertThrows(AppException.class, () -> authService.logout("invalid.jwt.token", null));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }
    }

    // =========================================================================
    // 4. REFRESH TESTS
    // =========================================================================
    @Nested
    @DisplayName("Refresh Branch Coverage")
    class RefreshTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Throws UNAUTHENTICATED when token is null or blank")
        void refresh_ThrowsUnauthenticated_WhenTokenMissing(String token) {
            AppException exception = assertThrows(AppException.class, () -> authService.refresh(token));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when token format is malformed")
        void refresh_ThrowsUnauthenticated_WhenTokenMalformed() {
            AppException exception = assertThrows(AppException.class, () -> authService.refresh("not-a-token"));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when refresh token is already revoked in Redis")
        void refresh_ThrowsUnauthenticated_WhenTokenAlreadyRevoked() throws Exception {
            String jti = UUID.randomUUID().toString();
            String token = createSignedToken("REFRESH", UUID.randomUUID().toString(), jti, Instant.now().plusSeconds(3600));

            when(valueOperations.setIfAbsent(eq("auth:revoked:refresh:" + jti), eq("1"), any(Duration.class)))
                    .thenReturn(false);

            AppException exception = assertThrows(AppException.class, () -> authService.refresh(token));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }

        @Test
        @DisplayName("Rejects a refresh token that expires before it can be consumed")
        void refresh_RejectsExpiredTokenBeforeRedisOrDatabase() throws Exception {
            UUID userId = UUID.randomUUID();
            String token = createSignedToken("REFRESH", userId.toString(), UUID.randomUUID().toString(),
                    Instant.now().minusSeconds(10));

            AppException exception = assertThrows(AppException.class, () -> authService.refresh(token));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            verifyNoInteractions(redisTemplate, valueOperations, userRepository);
        }

        @Test
        void refreshRejectsBannedAccount() throws Exception {
            UserEntity user = createUser("banned_user", "hashed_pwd", UserStatus.BANNED);
            String token = createSignedToken("REFRESH", user.getId().toString(), UUID.randomUUID().toString(),
                    Instant.now().plusSeconds(3600));
            when(valueOperations.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(true);
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

            AppException exception = assertThrows(AppException.class, () -> authService.refresh(token));

            assertEquals(ErrorCode.USER_NOT_ACTIVE, exception.getErrorCode());
        }

        @Test
        @DisplayName("Throws USER_NOT_FOUND when user ID in token does not exist in repository")
        void refresh_ThrowsUserNotFound_WhenUserDoesNotExist() throws Exception {
            UUID userId = UUID.randomUUID();
            String jti = UUID.randomUUID().toString();
            String token = createSignedToken("REFRESH", userId.toString(), jti, Instant.now().plusSeconds(3600));

            when(valueOperations.setIfAbsent(eq("auth:revoked:refresh:" + jti), eq("1"), any(Duration.class)))
                    .thenReturn(true);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            AppException exception = assertThrows(AppException.class, () -> authService.refresh(token));
            assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        }

        @Test
        @DisplayName("Successfully refreshes token and issues fresh access and refresh tokens")
        void refresh_Success() throws Exception {
            UUID userId = UUID.randomUUID();
            String jti = UUID.randomUUID().toString();
            String token = createSignedToken("REFRESH", userId.toString(), jti, Instant.now().plusSeconds(3600));

            UserEntity user = createUser("valid_user", "hashed_pwd", UserStatus.ACTIVE);
            user.setId(userId);
            user.setRoles(new HashSet<>());

            when(valueOperations.setIfAbsent(eq("auth:revoked:refresh:" + jti), eq("1"), any(Duration.class)))
                    .thenReturn(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            AuthResponse response = authService.refresh(token);

            assertNotNull(response);
            assertNotNull(response.getAccessToken());
            assertNotNull(response.getRefreshToken());

            SignedJWT newAccess = SignedJWT.parse(response.getAccessToken());
            assertEquals(userId.toString(), newAccess.getJWTClaimsSet().getSubject());
            assertEquals("ACCESS", newAccess.getJWTClaimsSet().getStringClaim("type"));
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================
    private UserEntity createUser(String username, String passwordHash, UserStatus status) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setStatus(status);
        user.setEmail(username + "@example.com");
        user.setEmailVerified(true);
        user.setRoles(new HashSet<>());
        return user;
    }

    private String createSignedToken(String type, String subject, String jti, Instant expiry) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .jwtID(jti)
                .expirationTime(Date.from(expiry))
                .claim("type", type)
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signedJWT.sign(new MACSigner(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)));
        return signedJWT.serialize();
    }
}
