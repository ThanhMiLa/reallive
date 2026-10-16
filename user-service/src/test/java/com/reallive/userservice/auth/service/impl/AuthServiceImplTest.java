package com.reallive.userservice.auth.service.impl;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.adapter.kafka.PasswordResetEmailPublisher;
import com.reallive.userservice.auth.adapter.redis.AuthStateStore;
import com.reallive.userservice.auth.adapter.redis.PasswordResetOtpStore;
import com.reallive.userservice.auth.adapter.redis.RedisAuthStateStore;
import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.request.ChangePasswordRequest;
import com.reallive.userservice.auth.dto.request.ForgotPasswordRequest;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.dto.request.ResetPasswordRequest;
import com.reallive.userservice.auth.dto.request.VerifyResetOtpRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.dto.response.ForgotPasswordResponse;
import com.reallive.userservice.auth.entity.PermissionEntity;
import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.util.PasswordResetOtpHasher;
import com.reallive.userservice.auth.util.UserAuthoritiesMapper;
import com.reallive.userservice.client.wallet.WalletClient;
import com.reallive.userservice.client.wallet.WalletCreationResponse;
import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.security.TokenIssuancePolicy;
import com.reallive.userservice.user.adapter.kafka.message.EmailType;
import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;
import com.reallive.userservice.user.dto.response.EmailVerificationCooldownResult;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpCheckResult;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private static final String SIGNING_KEY = "0123456789012345678901234567890123456789012345678901234567890123";

    private UserRepository userRepository;
    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private RoleRepository roleRepository;
    private ProfileRepository profileRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private PasswordResetOtpStore passwordResetOtpStore;
    private PasswordResetEmailPublisher passwordResetEmailPublisher;
    private PasswordResetOtpHasher passwordResetOtpHasher;
    private AuthServiceImpl authService;
    private AuthStateStore authStateStore;
    private WalletClient walletClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        roleRepository = mock(RoleRepository.class);
        profileRepository = mock(ProfileRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        passwordResetOtpStore = mock(PasswordResetOtpStore.class);
        passwordResetEmailPublisher = mock(PasswordResetEmailPublisher.class);
        passwordResetOtpHasher = mock(PasswordResetOtpHasher.class);
        walletClient = mock(WalletClient.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        authStateStore = mock(AuthStateStore.class);
        authService = new AuthServiceImpl(
                userRepository,
                userMapper,
                passwordEncoder,
                roleRepository,
                issuer(authStateStore, SIGNING_KEY),
                profileRepository,
                passwordResetOtpStore,
                passwordResetEmailPublisher,
                passwordResetOtpHasher,
                walletClient
        );

        ReflectionTestUtils.setField(authService, "otpTtlSeconds", 60L);
        ReflectionTestUtils.setField(authService, "resetTokenTtlSeconds", 600L);
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
        @DisplayName("Issuance policy rejects a banned account with USER_NOT_ACTIVE")
        void login_ThrowsUnauthenticated_WhenUserInactive() {
            AuthRequest request = AuthRequest.builder()
                    .username("banned_user")
                    .password("secret123")
                    .build();

            UserEntity user = createUser("banned_user", "hashed_pwd", UserStatus.BANNED);
            when(userRepository.findByUsername("banned_user")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("secret123", "hashed_pwd")).thenReturn(true);

            AppException exception = assertThrows(AppException.class, () -> authService.login(request));
            assertEquals(ErrorCode.USER_NOT_ACTIVE, exception.getErrorCode());
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
            assertNull(accessToken.getJWTClaimsSet().getStringClaim("scope"));
            verify(authStateStore).publish(any(), eq(List.of()), eq(true));
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
            assertNull(accessToken.getJWTClaimsSet().getStringClaim("scope"));
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
            verifyNoInteractions(profileRepository, walletClient);
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
            verifyNoInteractions(profileRepository, walletClient);
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
            verifyNoInteractions(profileRepository, walletClient);
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
            verifyNoInteractions(profileRepository, walletClient);
        }

        @Test
        @DisplayName("Registers user, saves a profile with the username as display name, then logs in")
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
            when(walletClient.createWallet(savedUser.getId())).thenReturn(ResponseEntity.ok(
                    ApiResponse.<WalletCreationResponse>builder().result(WalletCreationResponse.builder()
                            .id(UUID.randomUUID()).userId(savedUser.getId()).balance(0L).build()).build()));

            // Stubs for automatic login call
            when(userRepository.findByUsername("new_user")).thenReturn(Optional.of(savedUser));
            when(passwordEncoder.matches("password123", "encoded_pwd")).thenReturn(true);

            AuthResponse response = authService.register(request);

            RoleEntity streamerRole = RoleEntity.builder().id(UUID.randomUUID()).name("STREAMER").build();
            mappedUser.getRoles().add(streamerRole);
            assertEquals(Set.of(roleUser, streamerRole), mappedUser.getRoles());
            assertNotNull(response);
            assertNotNull(response.getAccessToken());
            assertNotNull(response.getRefreshToken());
            ArgumentCaptor<ProfileEntity> profileCaptor = ArgumentCaptor.forClass(ProfileEntity.class);
            InOrder registrationOrder = inOrder(userRepository, profileRepository, walletClient);
            registrationOrder.verify(userRepository).save(mappedUser);
            registrationOrder.verify(profileRepository).save(profileCaptor.capture());
            registrationOrder.verify(walletClient).createWallet(savedUser.getId());
            registrationOrder.verify(userRepository).findByUsername("new_user");
            verifyNoMoreInteractions(profileRepository);
            verifyNoMoreInteractions(walletClient);

            ProfileEntity profile = profileCaptor.getValue();
            assertSame(savedUser, profile.getUser());
            assertEquals("new_user", profile.getDisplayName());
            assertNull(profile.getAvatarUrl());
            assertNull(profile.getDob());
        }

        @Test
        @DisplayName("Does not create a profile or log in when saving the user fails")
        void register_DoesNotSaveProfile_WhenUserSaveFails() {
            RegisterRequest request = createRegistrationRequest();
            UserEntity mappedUser = prepareRegistration(request);
            DataIntegrityViolationException failure = new DataIntegrityViolationException("User insert failed");
            when(userRepository.save(mappedUser)).thenThrow(failure);

            assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                    () -> authService.register(request)));

            verifyNoInteractions(profileRepository);
            verify(userRepository, never()).findByUsername(any());
            verifyNoInteractions(walletClient);
        }

        @Test
        @DisplayName("Propagates profile save failure and does not log in")
        void register_DoesNotLogin_WhenProfileSaveFails() {
            RegisterRequest request = createRegistrationRequest();
            UserEntity mappedUser = prepareRegistration(request);
            UserEntity savedUser = createUser("new_user", "encoded_pwd", UserStatus.ACTIVE);
            when(userRepository.save(mappedUser)).thenReturn(savedUser);
            DataIntegrityViolationException failure = new DataIntegrityViolationException("Profile insert failed");
            when(profileRepository.save(any(ProfileEntity.class))).thenThrow(failure);

            assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                    () -> authService.register(request)));

            verify(userRepository).save(mappedUser);
            verify(profileRepository).save(any(ProfileEntity.class));
            verify(userRepository, never()).findByUsername(any());
            verifyNoInteractions(walletClient);
        }

        @Test
        void registrationMapsApplicationFailureFromWalletAndDoesNotIssueTokens() {
            RegisterRequest request = createRegistrationRequest();
            UserEntity savedUser = prepareSavedRegistration(request);
            when(walletClient.createWallet(savedUser.getId()))
                    .thenThrow(new AppException(ErrorCode.UNAUTHENTICATED));

            AppException failure = assertThrows(AppException.class, () -> authService.register(request));

            assertEquals(ErrorCode.PAYMENT_SERVICE_UNAVAILABLE, failure.getErrorCode());
            verify(walletClient).createWallet(savedUser.getId());
            verify(userRepository, never()).findByUsername(any());
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
        void registrationCurrentlyPropagatesFeignHttpFailuresWithoutIssuingTokens(int status) {
            RegisterRequest request = createRegistrationRequest();
            UserEntity savedUser = prepareSavedRegistration(request);
            FeignException failure = FeignException.errorStatus("WalletClient#createWallet",
                    Response.builder().status(status).reason("Payment request failed")
                            .headers(Map.of()).request(walletRequest()).build());
            when(walletClient.createWallet(savedUser.getId())).thenThrow(failure);

            assertSame(failure, assertThrows(FeignException.class, () -> authService.register(request)));
            verify(userRepository, never()).findByUsername(any());
        }

        @Test
        void registrationCurrentlyPropagatesReadTimeoutWithoutIssuingTokens() {
            RegisterRequest request = createRegistrationRequest();
            UserEntity savedUser = prepareSavedRegistration(request);
            RetryableException failure = new RetryableException(-1, "Read timed out", Request.HttpMethod.POST,
                    new SocketTimeoutException("Read timed out"), (Long) null, walletRequest());
            when(walletClient.createWallet(savedUser.getId())).thenThrow(failure);

            assertSame(failure, assertThrows(RetryableException.class, () -> authService.register(request)));
            verify(userRepository, never()).findByUsername(any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"missing-response", "missing-body", "missing-result"})
        void registrationCurrentlyRejectsIncompletePaymentReplyWithNullPointerException(String reply) {
            RegisterRequest request = createRegistrationRequest();
            UserEntity savedUser = prepareSavedRegistration(request);
            ResponseEntity<ApiResponse<WalletCreationResponse>> response = switch (reply) {
                case "missing-response" -> null;
                case "missing-body" -> ResponseEntity.ok().build();
                default -> ResponseEntity.ok(ApiResponse.<WalletCreationResponse>builder().build());
            };
            when(walletClient.createWallet(savedUser.getId())).thenReturn(response);

            assertThrows(NullPointerException.class, () -> authService.register(request));
            verify(userRepository, never()).findByUsername(any());
        }

        @Test
        void loginFailureAfterWalletCreationStillPropagates() {
            RegisterRequest request = createRegistrationRequest();
            UserEntity savedUser = prepareSavedRegistration(request);
            when(walletClient.createWallet(savedUser.getId())).thenReturn(ResponseEntity.ok(
                    ApiResponse.<WalletCreationResponse>builder().result(WalletCreationResponse.builder()
                            .id(UUID.randomUUID()).userId(savedUser.getId()).balance(0L).build()).build()));
            when(userRepository.findByUsername(request.getUsername())).thenReturn(Optional.empty());

            AppException failure = assertThrows(AppException.class, () -> authService.register(request));

            assertEquals(ErrorCode.UNAUTHENTICATED, failure.getErrorCode());
            verify(walletClient).createWallet(savedUser.getId());
        }

        private UserEntity prepareSavedRegistration(RegisterRequest request) {
            UserEntity mappedUser = prepareRegistration(request);
            UserEntity savedUser = createUser(request.getUsername(), "encoded_pwd", UserStatus.ACTIVE);
            when(userRepository.save(mappedUser)).thenReturn(savedUser);
            return savedUser;
        }

        private Request walletRequest() {
            return Request.create(Request.HttpMethod.POST, "http://payment/internal/api/wallets/create/user-id",
                    Map.of(), (byte[]) null, StandardCharsets.UTF_8, null);
        }

        private RegisterRequest createRegistrationRequest() {
            return RegisterRequest.builder()
                    .username("new_user")
                    .email("new@example.com")
                    .password("password123")
                    .confirmPassword("password123")
                    .build();
        }

        private UserEntity prepareRegistration(RegisterRequest request) {
            UserEntity mappedUser = new UserEntity();
            mappedUser.setUsername(request.getUsername());
            RoleEntity role = new RoleEntity();
            role.setName("USER");
            when(userMapper.toUserEntity(request)).thenReturn(mappedUser);
            when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded_pwd");
            when(roleRepository.findByName("USER")).thenReturn(Optional.of(role));
            return mappedUser;
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
        @DisplayName("Rejects logout without accessing Redis when both tokens are null or blank")
        void logout_ThrowsUnauthenticated_WhenTokensNullOrBlank(String token) {
            AppException exception = assertThrows(AppException.class, () -> authService.logout(token, token));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Rejects logout without accessing Redis when refresh token is missing")
        void logout_ThrowsUnauthenticated_WhenRefreshTokenMissing(String refreshToken) throws Exception {
            String jti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", "00000000-0000-0000-0000-000000000123", jti, Instant.now().plusSeconds(300));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.logout(accessToken, refreshToken));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Rejects logout without accessing Redis when access token is missing")
        void logout_ThrowsUnauthenticated_WhenAccessTokenMissing(String accessToken) throws Exception {
            String jti = UUID.randomUUID().toString();
            String refreshToken = createSignedToken("REFRESH", "00000000-0000-0000-0000-000000000123", jti, Instant.now().plusSeconds(3600));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.logout(accessToken, refreshToken));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Revokes both access and refresh tokens when both are valid")
        void logout_RevokesBothTokens() throws Exception {
            String accessJti = UUID.randomUUID().toString();
            String refreshJti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", "00000000-0000-0000-0000-000000000123", accessJti, Instant.now().plusSeconds(300));
            String refreshToken = createSignedToken("REFRESH", "00000000-0000-0000-0000-000000000123", refreshJti, Instant.now().plusSeconds(3600));

            when(valueOperations.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(true);

            authService.logout(accessToken, refreshToken);

            verify(authStateStore).revoke(ArgumentMatchers.argThat(values -> values.size() == 2));
        }

        @Test
        @DisplayName("Ignores expired access token and still revokes the valid refresh token")
        void logout_IgnoresExpiredAccessToken() throws Exception {
            String accessJti = UUID.randomUUID().toString();
            String refreshJti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", "00000000-0000-0000-0000-000000000123", accessJti,
                    Instant.now().minusSeconds(60));
            String refreshToken = createSignedToken("REFRESH", "00000000-0000-0000-0000-000000000123", refreshJti,
                    Instant.now().plusSeconds(3600));
            when(valueOperations.setIfAbsent(eq("auth:revoked:refresh:" + refreshJti), eq("1"), any(Duration.class)))
                    .thenReturn(true);

            authService.logout(accessToken, refreshToken);

            verify(authStateStore).revoke(ArgumentMatchers.argThat(values -> values.size() == 1
                    && values.getFirst().key().equals("auth:revoked:refresh:" + refreshJti)));
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when token was already revoked in Redis")
        void logout_ThrowsUnauthenticated_WhenTokenAlreadyRevoked() throws Exception {
            String jti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", "00000000-0000-0000-0000-000000000123", jti, Instant.now().plusSeconds(300));
            String refreshJti = UUID.randomUUID().toString();
            String refreshToken = createSignedToken("REFRESH", "00000000-0000-0000-0000-000000000123", refreshJti,
                    Instant.now().plusSeconds(3600));

            doThrow(new AppException(ErrorCode.UNAUTHENTICATED)).when(authStateStore).revoke(any());

            AppException exception = assertThrows(AppException.class, () -> authService.logout(accessToken, refreshToken));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());

        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when token format is malformed")
        void logout_ThrowsUnauthenticated_WhenTokenMalformed() throws Exception {
            String refreshToken = createSignedToken("REFRESH", "00000000-0000-0000-0000-000000000123", UUID.randomUUID().toString(),
                    Instant.now().plusSeconds(3600));
            AppException exception = assertThrows(AppException.class,
                    () -> authService.logout("invalid.jwt.token", refreshToken));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            verifyNoInteractions(redisTemplate, valueOperations);
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
            UserEntity user = createTestUser();
            String token = createSignedToken("REFRESH", user.getId().toString(), jti, Instant.now().plusSeconds(3600));
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            doThrow(new AppException(ErrorCode.UNAUTHENTICATED)).when(authStateStore).publish(any(), any(), eq(true));

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

        @Test
        @EnabledIfSystemProperty(named = "gateway.test.redis-port", matches = "[0-9]+")
        void onlyOneConcurrentRefreshCanConsumeTokenInRedis() throws Exception {
            String signingKey = "refresh-concurrency-key-".repeat(4);
            String jti = UUID.randomUUID().toString();
            UserEntity user = new UserEntity();
            user.setId(UUID.randomUUID());
            user.setStatus(UserStatus.ACTIVE);
            user.setRoles(Set.of());
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1",
                    Integer.parseInt(System.getProperty("gateway.test.redis-port")));
            configuration.setDatabase(4);
            LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
            connectionFactory.afterPropertiesSet();
            connectionFactory.start();
            StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
            try (var executor = Executors.newFixedThreadPool(2)) {
                UserRepository users = mock(UserRepository.class);
                when(users.findById(user.getId())).thenReturn(Optional.of(user));
                AuthServiceImpl service = new AuthServiceImpl(users, mock(UserMapper.class),
                        mock(PasswordEncoder.class), mock(RoleRepository.class), issuer(new RedisAuthStateStore(redis), signingKey), mock(ProfileRepository.class),
                        mock(PasswordResetOtpStore.class), mock(PasswordResetEmailPublisher.class), mock(PasswordResetOtpHasher.class),
                        mock(WalletClient.class));
                JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(user.getId().toString()).jwtID(jti)
                        .issuer("reallive.com").issueTime(Date.from(Instant.now().minusSeconds(1)))
                        .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("type", "REFRESH").build();
                SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
                token.sign(new MACSigner(signingKey.getBytes(StandardCharsets.UTF_8)));
                CyclicBarrier start = new CyclicBarrier(2);
                var first = executor.submit(() -> refresh(service, token.serialize(), start));
                var second = executor.submit(() -> refresh(service, token.serialize(), start));

                assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
                assertEquals("1", redis.opsForValue().get("auth:revoked:refresh:" + jti));
            } finally {
                try {
                    redis.delete("auth:revoked:refresh:" + jti);
                } finally {
                    connectionFactory.destroy();
                }
            }
        }

        private int refresh(AuthServiceImpl service, String token, CyclicBarrier start) throws Exception {
            start.await(5, TimeUnit.SECONDS);
            try {
                service.refresh(token);
                return 1;
            } catch (AppException exception) {
                assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
                return 0;
            }
        }
    }

    // =========================================================================
    // 5. CHANGE PASSWORD TESTS
    // =========================================================================
    @Nested
    @DisplayName("Change Password Branch Coverage")
    class ChangePasswordTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Throws UNAUTHENTICATED when refresh token is null or blank")
        void changePassword_ThrowsUnauthenticated_WhenRefreshTokenMissing(String refreshToken) {
            UserEntity user = createUser("active_user", "hashed_pwd", UserStatus.ACTIVE);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), "some-access-token", refreshToken, request));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            verify(passwordEncoder, never()).encode(any());
            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Throws USER_NOT_FOUND when user ID does not exist")
        void changePassword_ThrowsUserNotFound_WhenUserDoesNotExist() {
            UUID userId = UUID.randomUUID();
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(userId, "access-token", "refresh-token", request));
            assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
            verifyNoInteractions(passwordEncoder, redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Business password validation does not repeat gateway account-status checks")
        void changePasswordValidatesPasswordWithoutRepeatingAccountStatusCheck() {
            UserEntity user = createUser("banned_user", "hashed_pwd", UserStatus.BANNED);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), "access-token", "refresh-token", request));
            assertEquals(ErrorCode.CURRENT_PASSWORD_INCORRECT, exception.getErrorCode());
            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Throws PASSWORD_NOT_SET when user password hash is null")
        void changePassword_ThrowsPasswordNotSet_WhenPasswordHashNull() {
            UserEntity user = createUser("oauth_user", null, UserStatus.ACTIVE);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), "access-token", "refresh-token", request));
            assertEquals(ErrorCode.PASSWORD_NOT_SET, exception.getErrorCode());
            verifyNoInteractions(passwordEncoder, redisTemplate, valueOperations);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("Throws PASSWORD_NOT_SET when user password hash is blank")
        void changePassword_ThrowsPasswordNotSet_WhenPasswordHashBlank(String blankHash) {
            UserEntity user = createUser("oauth_user", blankHash, UserStatus.ACTIVE);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), "access-token", "refresh-token", request));
            assertEquals(ErrorCode.PASSWORD_NOT_SET, exception.getErrorCode());
            verifyNoInteractions(passwordEncoder, redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Throws PASSWORD_NOT_MATCH when new password and confirm password differ")
        void changePassword_ThrowsPasswordNotMatch_WhenNewAndConfirmPasswordDiffer() {
            UserEntity user = createUser("active_user", "hashed_pwd", UserStatus.ACTIVE);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("differentSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), "access-token", "refresh-token", request));
            assertEquals(ErrorCode.PASSWORD_NOT_MATCH, exception.getErrorCode());
            verifyNoInteractions(passwordEncoder, redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Throws CURRENT_PASSWORD_INCORRECT when current password does not match DB hash")
        void changePassword_ThrowsCurrentPasswordIncorrect_WhenCurrentPasswordWrong() {
            UserEntity user = createUser("active_user", "hashed_pwd", UserStatus.ACTIVE);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("wrongOldSecret")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrongOldSecret", "hashed_pwd")).thenReturn(false);

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), "access-token", "refresh-token", request));
            assertEquals(ErrorCode.CURRENT_PASSWORD_INCORRECT, exception.getErrorCode());
            verify(passwordEncoder, never()).encode(any());
            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Throws NEW_PASSWORD_SAME_AS_CURRENT when new password equals current password")
        void changePassword_ThrowsNewPasswordSameAsCurrent_WhenNewPasswordMatchesCurrent() {
            UserEntity user = createUser("active_user", "hashed_pwd", UserStatus.ACTIVE);
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("oldSecret123")
                    .confirmPassword("oldSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("oldSecret123", "hashed_pwd")).thenReturn(true);

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), "access-token", "refresh-token", request));
            assertEquals(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT, exception.getErrorCode());
            verify(passwordEncoder, never()).encode(any());
            verifyNoInteractions(redisTemplate, valueOperations);
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when access token revocation fails in Redis")
        void changePassword_ThrowsUnauthenticated_WhenAccessTokenRevocationFails() throws Exception {
            UserEntity user = createUser("active_user", "hashed_pwd", UserStatus.ACTIVE);
            String accessJti = UUID.randomUUID().toString();
            String refreshJti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", user.getId().toString(), accessJti,
                    Instant.now().plusSeconds(300));
            String refreshToken = createSignedToken("REFRESH", user.getId().toString(), refreshJti,
                    Instant.now().plusSeconds(3600));
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("oldSecret123", "hashed_pwd")).thenReturn(true);
            when(passwordEncoder.matches("newSecret123", "hashed_pwd")).thenReturn(false);
            when(passwordEncoder.encode("newSecret123")).thenReturn("new_hashed_pwd");
            when(valueOperations.setIfAbsent(eq("auth:revoked:access:" + accessJti), eq("1"), any(Duration.class)))
                    .thenReturn(false);

            doThrow(new AppException(ErrorCode.UNAUTHENTICATED)).when(authStateStore).publish(any(), any(), eq(true));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), accessToken, refreshToken, request));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }

        @Test
        @DisplayName("Successfully changes password, revokes both tokens, and returns new tokens")
        void changePassword_Success_RevokesBothTokens_AndIssuesNewTokens() throws Exception {
            UserEntity user = createUser("active_user", "hashed_pwd", UserStatus.ACTIVE);
            String accessJti = UUID.randomUUID().toString();
            String refreshJti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", user.getId().toString(), accessJti,
                    Instant.now().plusSeconds(300));
            String refreshToken = createSignedToken("REFRESH", user.getId().toString(), refreshJti,
                    Instant.now().plusSeconds(3600));

            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("oldSecret123", "hashed_pwd")).thenReturn(true);
            when(passwordEncoder.matches("newSecret123", "hashed_pwd")).thenReturn(false);
            when(passwordEncoder.encode("newSecret123")).thenReturn("new_hashed_pwd");
            when(valueOperations.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(true);

            AuthResponse response = authService.changePassword(user.getId(), accessToken, refreshToken, request);

            assertNotNull(response);
            assertNotNull(response.getAccessToken());
            assertNotNull(response.getRefreshToken());
            assertEquals("new_hashed_pwd", user.getPasswordHash());
            verify(userRepository).save(user);

            verify(authStateStore).publish(any(), ArgumentMatchers.argThat(values -> values.size() == 2), eq(true));

            SignedJWT newAccess = SignedJWT.parse(response.getAccessToken());
            assertEquals(user.getId().toString(), newAccess.getJWTClaimsSet().getSubject());
            assertEquals("ACCESS", newAccess.getJWTClaimsSet().getStringClaim("type"));
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when refresh token revocation fails in Redis")
        void changePassword_ThrowsUnauthenticated_WhenRefreshTokenRevocationFails() throws Exception {
            UserEntity user = createUser("active_user", "hashed_pwd", UserStatus.ACTIVE);
            String accessJti = UUID.randomUUID().toString();
            String refreshJti = UUID.randomUUID().toString();
            String accessToken = createSignedToken("ACCESS", user.getId().toString(), accessJti,
                    Instant.now().plusSeconds(300));
            String refreshToken = createSignedToken("REFRESH", user.getId().toString(), refreshJti,
                    Instant.now().plusSeconds(3600));
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldSecret123")
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("oldSecret123", "hashed_pwd")).thenReturn(true);
            when(passwordEncoder.matches("newSecret123", "hashed_pwd")).thenReturn(false);
            when(passwordEncoder.encode("newSecret123")).thenReturn("new_hashed_pwd");
            when(valueOperations.setIfAbsent(eq("auth:revoked:access:" + accessJti), eq("1"), any(Duration.class)))
                    .thenReturn(true);
            when(valueOperations.setIfAbsent(eq("auth:revoked:refresh:" + refreshJti), eq("1"), any(Duration.class)))
                    .thenReturn(false);

            doThrow(new AppException(ErrorCode.UNAUTHENTICATED)).when(authStateStore).publish(any(), any(), eq(true));

            AppException exception = assertThrows(AppException.class,
                    () -> authService.changePassword(user.getId(), accessToken, refreshToken, request));
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================
    private AuthTokenServiceImpl issuer(AuthStateStore store, String key) {
        var issuer = new AuthTokenServiceImpl(store, new UserAuthoritiesMapper(), new TokenIssuancePolicy(), new ObjectMapper());
        ReflectionTestUtils.setField(issuer, "signerKey", key);
        ReflectionTestUtils.setField(issuer, "accessTokenTtl", 900L);
        ReflectionTestUtils.setField(issuer, "refreshTokenTtl", 2592000L);
        ReflectionTestUtils.setField(issuer, "ttlGrace", Duration.ofSeconds(60));
        return issuer;
    }

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

    private UserEntity createTestUser() {
        return createUser("testuser", "hashed-password", UserStatus.ACTIVE);
    }

    private String createSignedToken(String type, String subject, String jti, Instant expiry) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("reallive.com")
                .issueTime(Date.from(expiry.minusSeconds(7200)))
                .jwtID(jti)
                .expirationTime(Date.from(expiry))
                .claim("type", type)
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signedJWT.sign(new MACSigner(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)));
        return signedJWT.serialize();
    }

    // =========================================================================
    // 6. FORGOT PASSWORD TESTS
    // =========================================================================
    @Nested
    @DisplayName("Forgot Password Branch Coverage")
    class ForgotPasswordTests {

        @Test
        @DisplayName("Throws USER_NOT_FOUND when email does not exist")
        void forgotPassword_UserNotFound() {
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("unknown@example.com").build();
            when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

            AppException ex = assertThrows(AppException.class, () -> authService.forgotPassword(request));
            assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
            verify(passwordResetOtpStore, never()).hasCooldown(any());
        }

        @Test
        @DisplayName("Public recovery does not repeat account-status checks")
        void forgotPassword_DoesNotRepeatAccountStatusCheck() {
            UserEntity user = createTestUser();
            user.setStatus(UserStatus.BANNED);
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenReturn(false);
            when(passwordResetOtpHasher.hash(eq(user.getId()), eq("user@example.com"), anyString())).thenReturn("hashed-otp");
            when(passwordResetOtpStore.save(eq(user.getId()), eq("hashed-otp")))
                    .thenReturn(new EmailVerificationCooldownResult(true, Duration.ZERO));

            ForgotPasswordResponse response = authService.forgotPassword(request);

            assertNotNull(response);
            assertNotNull(response.getExpiresAt());
            assertTrue(response.getExpiresAt().isAfter(Instant.now()));

            ArgumentCaptor<EmailVerificationRequestedMessage> messageCaptor =
                    ArgumentCaptor.forClass(EmailVerificationRequestedMessage.class);
            verify(passwordResetEmailPublisher).publish(messageCaptor.capture());
            EmailVerificationRequestedMessage published = messageCaptor.getValue();
            assertEquals(user.getId(), published.getUserId());
            assertEquals(EmailType.PASSWORD_RESET, published.getEmailType());
            assertEquals("user@example.com", published.getRecipientEmail());
            assertNotNull(published.getOtp());

        }

        @Test
        @DisplayName("Throws OTP_COOLDOWN when cooldown is active in store")
        void forgotPassword_OtpCooldownActive() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenReturn(true);

            AppException ex = assertThrows(AppException.class, () -> authService.forgotPassword(request));
            assertEquals(ErrorCode.OTP_COOLDOWN, ex.getErrorCode());
            verify(passwordResetOtpHasher, never()).hash(any(), any(), any());
        }

        @Test
        @DisplayName("Throws OTP_REQUEST_UNAVAILABLE when checking cooldown fails in Redis")
        void forgotPassword_CooldownStoreThrows() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenThrow(new RedisConnectionFailureException("Redis down"));

            AppException ex = assertThrows(AppException.class, () -> authService.forgotPassword(request));
            assertEquals(ErrorCode.OTP_REQUEST_UNAVAILABLE, ex.getErrorCode());
        }

        @Test
        @DisplayName("Throws OTP_CONFIGURATION_INVALID when hasher throws IllegalStateException")
        void forgotPassword_HasherThrows() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenReturn(false);
            when(passwordResetOtpHasher.hash(eq(user.getId()), eq("user@example.com"), anyString()))
                    .thenThrow(new IllegalStateException("Missing HMAC key"));

            AppException ex = assertThrows(AppException.class, () -> authService.forgotPassword(request));
            assertEquals(ErrorCode.OTP_CONFIGURATION_INVALID, ex.getErrorCode());
        }

        @Test
        @DisplayName("Throws OTP_COOLDOWN when store save returns acquired = false")
        void forgotPassword_SaveAcquiredFalse() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenReturn(false);
            when(passwordResetOtpHasher.hash(eq(user.getId()), eq("user@example.com"), anyString())).thenReturn("hashed-otp");
            when(passwordResetOtpStore.save(eq(user.getId()), eq("hashed-otp")))
                    .thenReturn(new EmailVerificationCooldownResult(false, Duration.ofSeconds(30)));

            AppException ex = assertThrows(AppException.class, () -> authService.forgotPassword(request));
            assertEquals(ErrorCode.OTP_COOLDOWN, ex.getErrorCode());
            verify(passwordResetEmailPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("Throws OTP_REQUEST_UNAVAILABLE when store save throws DataAccessException")
        void forgotPassword_SaveStoreThrows() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenReturn(false);
            when(passwordResetOtpHasher.hash(eq(user.getId()), eq("user@example.com"), anyString())).thenReturn("hashed-otp");
            when(passwordResetOtpStore.save(eq(user.getId()), eq("hashed-otp")))
                    .thenThrow(new RedisConnectionFailureException("Redis down"));

            AppException ex = assertThrows(AppException.class, () -> authService.forgotPassword(request));
            assertEquals(ErrorCode.OTP_REQUEST_UNAVAILABLE, ex.getErrorCode());
        }

        @Test
        @DisplayName("Throws OTP_REQUEST_UNAVAILABLE and cleans up OTP when Kafka publish fails before submission")
        void forgotPassword_KafkaPublishFailed_RollsBackRedis() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenReturn(false);
            when(passwordResetOtpHasher.hash(eq(user.getId()), eq("user@example.com"), anyString())).thenReturn("hashed-otp");
            when(passwordResetOtpStore.save(eq(user.getId()), eq("hashed-otp")))
                    .thenReturn(new EmailVerificationCooldownResult(true, Duration.ZERO));
            doThrow(new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED))
                    .when(passwordResetEmailPublisher).publish(any());

            AppException ex = assertThrows(AppException.class, () -> authService.forgotPassword(request));
            assertEquals(ErrorCode.OTP_REQUEST_UNAVAILABLE, ex.getErrorCode());
            verify(passwordResetOtpStore).deleteIfCurrent(user.getId(), "hashed-otp");
        }

        @Test
        @DisplayName("Throws OTP_REQUEST_UNAVAILABLE without deleting OTP when Kafka outcome is uncertain")
        void forgotPassword_KafkaPublishUncertain_DoesNotDeleteOtp() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenReturn(false);
            when(passwordResetOtpHasher.hash(eq(user.getId()), eq("user@example.com"), anyString())).thenReturn("hashed-otp");
            when(passwordResetOtpStore.save(eq(user.getId()), eq("hashed-otp")))
                    .thenReturn(new EmailVerificationCooldownResult(true, Duration.ZERO));
            doThrow(new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN))
                    .when(passwordResetEmailPublisher).publish(any());

            AppException ex = assertThrows(AppException.class, () -> authService.forgotPassword(request));
            assertEquals(ErrorCode.OTP_REQUEST_UNAVAILABLE, ex.getErrorCode());
            verify(passwordResetOtpStore, never()).deleteIfCurrent(any(), any());
        }

        @Test
        @DisplayName("Successfully creates OTP, publishes Kafka message, and returns expiresAt")
        void forgotPassword_Success() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            ForgotPasswordRequest request = ForgotPasswordRequest.builder().email("user@example.com").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpStore.hasCooldown(user.getId())).thenReturn(false);
            when(passwordResetOtpHasher.hash(eq(user.getId()), eq("user@example.com"), anyString())).thenReturn("hashed-otp");
            when(passwordResetOtpStore.save(eq(user.getId()), eq("hashed-otp")))
                    .thenReturn(new EmailVerificationCooldownResult(true, Duration.ZERO));

            ForgotPasswordResponse response = authService.forgotPassword(request);

            assertNotNull(response);
            assertNotNull(response.getExpiresAt());
            assertTrue(response.getExpiresAt().isAfter(Instant.now()));

            ArgumentCaptor<EmailVerificationRequestedMessage> messageCaptor =
                    ArgumentCaptor.forClass(EmailVerificationRequestedMessage.class);
            verify(passwordResetEmailPublisher).publish(messageCaptor.capture());
            EmailVerificationRequestedMessage published = messageCaptor.getValue();
            assertEquals(user.getId(), published.getUserId());
            assertEquals(EmailType.PASSWORD_RESET, published.getEmailType());
            assertEquals("user@example.com", published.getRecipientEmail());
            assertNotNull(published.getOtp());
        }
    }

    // =========================================================================
    // 7. VERIFY RESET OTP TESTS
    // =========================================================================
    @Nested
    @DisplayName("Verify Reset OTP Branch Coverage")
    class VerifyResetOtpTests {

        @Test
        @DisplayName("Throws USER_NOT_FOUND when email does not exist")
        void verifyResetOtp_UserNotFound() {
            VerifyResetOtpRequest request = VerifyResetOtpRequest.builder().email("unknown@example.com").otp("123456").build();
            when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

            AppException ex = assertThrows(AppException.class, () -> authService.verifyResetOtp(request));
            assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
        }

        @Test
        @DisplayName("Public recovery does not repeat account-status checks")
        void verifyResetOtp_DoesNotRepeatAccountStatusCheck() {
            UserEntity user = createTestUser();
            user.setStatus(UserStatus.BANNED);
            user.setEmail("user@example.com");
            VerifyResetOtpRequest request = VerifyResetOtpRequest.builder().email("user@example.com").otp("123456").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpHasher.hash(user.getId(), "user@example.com", "123456")).thenReturn("hashed-otp");
            when(passwordResetOtpStore.consume(user.getId(), "hashed-otp")).thenReturn(EmailVerificationOtpCheckResult.VALID);

            String token = authService.verifyResetOtp(request);

            assertNotNull(token);
            assertEquals(64, token.length()); // 32 bytes hex
            verify(passwordResetOtpStore).saveResetToken(eq(token), eq(user.getId()), eq(Duration.ofSeconds(600)));

        }

        @Test
        @DisplayName("Throws OTP_NOT_FOUND when OTP is expired or not found in store")
        void verifyResetOtp_OtpNotFound() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            VerifyResetOtpRequest request = VerifyResetOtpRequest.builder().email("user@example.com").otp("123456").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpHasher.hash(user.getId(), "user@example.com", "123456")).thenReturn("hashed-otp");
            when(passwordResetOtpStore.consume(user.getId(), "hashed-otp")).thenReturn(EmailVerificationOtpCheckResult.NOT_FOUND);

            AppException ex = assertThrows(AppException.class, () -> authService.verifyResetOtp(request));
            assertEquals(ErrorCode.OTP_NOT_FOUND, ex.getErrorCode());
        }

        @Test
        @DisplayName("Throws OTP_MISMATCH when OTP does not match")
        void verifyResetOtp_OtpMismatch() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            VerifyResetOtpRequest request = VerifyResetOtpRequest.builder().email("user@example.com").otp("123456").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpHasher.hash(user.getId(), "user@example.com", "123456")).thenReturn("hashed-otp");
            when(passwordResetOtpStore.consume(user.getId(), "hashed-otp")).thenReturn(EmailVerificationOtpCheckResult.INVALID);

            AppException ex = assertThrows(AppException.class, () -> authService.verifyResetOtp(request));
            assertEquals(ErrorCode.OTP_MISMATCH, ex.getErrorCode());
        }

        @Test
        @DisplayName("Throws OTP_VERIFICATION_UNAVAILABLE when consume throws DataAccessException")
        void verifyResetOtp_ConsumeStoreThrows() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            VerifyResetOtpRequest request = VerifyResetOtpRequest.builder().email("user@example.com").otp("123456").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpHasher.hash(user.getId(), "user@example.com", "123456")).thenReturn("hashed-otp");
            when(passwordResetOtpStore.consume(user.getId(), "hashed-otp")).thenThrow(new RedisConnectionFailureException("Redis down"));

            AppException ex = assertThrows(AppException.class, () -> authService.verifyResetOtp(request));
            assertEquals(ErrorCode.OTP_VERIFICATION_UNAVAILABLE, ex.getErrorCode());
        }

        @Test
        @DisplayName("Throws OTP_VERIFICATION_UNAVAILABLE when saveResetToken throws DataAccessException")
        void verifyResetOtp_SaveTokenStoreThrows() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            VerifyResetOtpRequest request = VerifyResetOtpRequest.builder().email("user@example.com").otp("123456").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpHasher.hash(user.getId(), "user@example.com", "123456")).thenReturn("hashed-otp");
            when(passwordResetOtpStore.consume(user.getId(), "hashed-otp")).thenReturn(EmailVerificationOtpCheckResult.VALID);
            doThrow(new RedisConnectionFailureException("Redis down"))
                    .when(passwordResetOtpStore).saveResetToken(anyString(), eq(user.getId()), any(Duration.class));

            AppException ex = assertThrows(AppException.class, () -> authService.verifyResetOtp(request));
            assertEquals(ErrorCode.OTP_VERIFICATION_UNAVAILABLE, ex.getErrorCode());
        }

        @Test
        @DisplayName("Successfully consumes OTP, persists reset token, and returns 64-character hex token")
        void verifyResetOtp_Success() {
            UserEntity user = createTestUser();
            user.setEmail("user@example.com");
            VerifyResetOtpRequest request = VerifyResetOtpRequest.builder().email("user@example.com").otp("123456").build();
            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(passwordResetOtpHasher.hash(user.getId(), "user@example.com", "123456")).thenReturn("hashed-otp");
            when(passwordResetOtpStore.consume(user.getId(), "hashed-otp")).thenReturn(EmailVerificationOtpCheckResult.VALID);

            String token = authService.verifyResetOtp(request);

            assertNotNull(token);
            assertEquals(64, token.length()); // 32 bytes hex
            verify(passwordResetOtpStore).saveResetToken(eq(token), eq(user.getId()), eq(Duration.ofSeconds(600)));
        }
    }

    // =========================================================================
    // 8. RESET PASSWORD TESTS
    // =========================================================================
    @Nested
    @DisplayName("Reset Password Branch Coverage")
    class ResetPasswordTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Throws UNAUTHENTICATED when reset token is null, empty or blank")
        void resetPassword_TokenMissingOrBlank(String token) {
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();

            AppException ex = assertThrows(AppException.class, () -> authService.resetPassword(token, request));
            assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
            verify(passwordResetOtpStore, never()).consumeResetToken(any());
        }

        @Test
        @DisplayName("Throws PASSWORD_NOT_MATCH when newPassword and confirmPassword do not match")
        void resetPassword_PasswordMismatch() {
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .newPassword("newSecret123")
                    .confirmPassword("differentSecret123")
                    .build();

            AppException ex = assertThrows(AppException.class, () -> authService.resetPassword("valid-token", request));
            assertEquals(ErrorCode.PASSWORD_NOT_MATCH, ex.getErrorCode());
            verify(passwordResetOtpStore, never()).consumeResetToken(any());
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when reset token is expired or invalid in store")
        void resetPassword_TokenNotFoundInStore() {
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();
            when(passwordResetOtpStore.consumeResetToken("expired-token")).thenReturn(Optional.empty());

            AppException ex = assertThrows(AppException.class, () -> authService.resetPassword("expired-token", request));
            assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
        }

        @Test
        @DisplayName("Throws UNAUTHENTICATED when store throws DataAccessException")
        void resetPassword_StoreThrows() {
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();
            when(passwordResetOtpStore.consumeResetToken("token")).thenThrow(new RedisConnectionFailureException("Redis down"));

            AppException ex = assertThrows(AppException.class, () -> authService.resetPassword("token", request));
            assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
        }

        @Test
        @DisplayName("Throws USER_NOT_FOUND when user associated with token does not exist")
        void resetPassword_UserNotFound() {
            UUID userId = UUID.randomUUID();
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .newPassword("newSecret123")
                    .confirmPassword("newSecret123")
                    .build();
            when(passwordResetOtpStore.consumeResetToken("valid-token")).thenReturn(Optional.of(userId));
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            AppException ex = assertThrows(AppException.class, () -> authService.resetPassword("valid-token", request));
            assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
        }

        @Test
        @DisplayName("Public password reset does not repeat account-status checks")
        void resetPassword_DoesNotRepeatAccountStatusCheck() {
            UserEntity user = createTestUser();
            user.setStatus(UserStatus.BANNED);
            user.setPasswordHash("hashed-old-secret");
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .newPassword("newBrandSecret123")
                    .confirmPassword("newBrandSecret123")
                    .build();
            when(passwordResetOtpStore.consumeResetToken("valid-token")).thenReturn(Optional.of(user.getId()));
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("newBrandSecret123", "hashed-old-secret")).thenReturn(false);
            when(passwordEncoder.encode("newBrandSecret123")).thenReturn("hashed-newBrandSecret123");

            authService.resetPassword("valid-token", request);

            assertEquals("hashed-newBrandSecret123", user.getPasswordHash());
            verify(userRepository).save(user);

        }

        @Test
        @DisplayName("Throws NEW_PASSWORD_SAME_AS_CURRENT when new password matches current password")
        void resetPassword_NewPasswordSameAsCurrent() {
            UserEntity user = createTestUser();
            user.setPasswordHash("hashed-old-secret");
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .newPassword("oldSecret123")
                    .confirmPassword("oldSecret123")
                    .build();
            when(passwordResetOtpStore.consumeResetToken("valid-token")).thenReturn(Optional.of(user.getId()));
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("oldSecret123", "hashed-old-secret")).thenReturn(true);

            AppException ex = assertThrows(AppException.class, () -> authService.resetPassword("valid-token", request));
            assertEquals(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT, ex.getErrorCode());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Successfully updates password and saves user when request is valid")
        void resetPassword_Success() {
            UserEntity user = createTestUser();
            user.setPasswordHash("hashed-old-secret");
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .newPassword("newBrandSecret123")
                    .confirmPassword("newBrandSecret123")
                    .build();
            when(passwordResetOtpStore.consumeResetToken("valid-token")).thenReturn(Optional.of(user.getId()));
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("newBrandSecret123", "hashed-old-secret")).thenReturn(false);
            when(passwordEncoder.encode("newBrandSecret123")).thenReturn("hashed-newBrandSecret123");

            authService.resetPassword("valid-token", request);

            assertEquals("hashed-newBrandSecret123", user.getPasswordHash());
            verify(userRepository).save(user);
        }
    }
}
