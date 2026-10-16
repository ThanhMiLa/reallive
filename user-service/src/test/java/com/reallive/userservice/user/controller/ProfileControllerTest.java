package com.reallive.userservice.user.controller;

import com.reallive.userservice.auth.adapter.redis.AuthStateStore;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.security.CustomJwtDecoder;
import com.reallive.userservice.common.service.R2StorageService;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import com.reallive.userservice.user.dto.request.UpdateProfileRequest;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import com.reallive.userservice.user.service.ProfileService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:user_profile_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.servlet.multipart.max-file-size=5MB",
        "spring.servlet.multipart.max-request-size=6MB",
        "server.tomcat.max-swallow-size=10MB"
})
@ActiveProfiles("test")
class ProfileControllerTest {
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @MockitoBean
    private AuthStateStore authStateStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private StreamerRepository streamerRepository;

    @MockitoBean
    private ChannelClientResolver channelClientResolver;

    @MockitoBean
    private CustomJwtDecoder jwtDecoder;

    @MockitoBean
    private R2StorageService storage;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${local.server.port}")
    private int port;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        streamerRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getMyProfileReturnsExactlyTheRequestedFields() throws Exception {
        UserEntity user = createUser("viewer");
        profileRepository.saveAndFlush(ProfileEntity.builder()
                .user(user)
                .displayName("Viewer")
                .avatarUrl("https://example.com/avatar.png")
                .dob(LocalDate.of(2000, 1, 1))
                .build());
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.length()").value(6))
                .andExpect(jsonPath("$.result.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.result.displayName").value("Viewer"))
                .andExpect(jsonPath("$.result.email").value("viewer@example.com"))
                .andExpect(jsonPath("$.result.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.result.dob").value("2000-01-01"))
                .andExpect(jsonPath("$.result.create_at").value(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(user.getCreatedAt())));
    }

    @Test
    void getMyProfileReturnsNullForOptionalFields() throws Exception {
        UserEntity user = createUser("viewer");
        profileRepository.saveAndFlush(ProfileEntity.builder().user(user).displayName("Viewer").build());
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(hasKey("avatarUrl")))
                .andExpect(jsonPath("$.result.avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.result").value(hasKey("dob")))
                .andExpect(jsonPath("$.result.dob").value(nullValue()));
    }

    @Test
    void getMyProfileIgnoresAnotherUserIdSuppliedInTheQuery() throws Exception {
        UserEntity viewer = createUser("viewer");
        UserEntity otherUser = createUser("other_user");
        profileRepository.saveAndFlush(ProfileEntity.builder().user(viewer).displayName("Viewer").build());
        profileRepository.saveAndFlush(ProfileEntity.builder().user(otherUser).displayName("Other User").build());
        authenticate(viewer.getId().toString());

        mockMvc.perform(get("/api/users/me").param("userId", otherUser.getId().toString()).cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(viewer.getId().toString()))
                .andExpect(jsonPath("$.result.email").value("viewer@example.com"));
    }

    @Test
    void getMyProfileRejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
    }

    @Test
    void getMyProfileRejectsInvalidTokens() throws Exception {
        when(jwtDecoder.decode("valid-access-token")).thenThrow(new BadJwtException("Invalid access token"));

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid"})
    void getMyProfileRejectsMissingOrInvalidUserIdsInTheToken(String subject) throws Exception {
        authenticate(subject);

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
    }

    @Test
    void getMyProfileReturnsNotFoundWhenTheProfileIsMissing() throws Exception {
        UserEntity user = createUser("viewer");
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PROFILE_NOT_FOUND.getCode()));
    }

    @Test
    void getMyProfileRejectsBannedAccounts() throws Exception {
        UserEntity user = createUser("viewer");
        user.setStatus(UserStatus.BANNED);
        user = userRepository.saveAndFlush(user);
        profileRepository.saveAndFlush(ProfileEntity.builder().user(user).displayName("Viewer").build());
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_ACTIVE.getCode()));
    }

    @Test
    void updateMyProfilePersistsAllFieldsAndIgnoresProtectedFields() throws Exception {
        UserEntity user = createProfile();
        UserEntity other = createUser("other_user");
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .file(avatar())
                        .param("displayName", "  New Viewer  ")
                        .param("dob", "2001-02-03")
                        .param("userId", other.getId().toString())
                        .param("email", "hacker@example.com")
                        .param("avatarUrl", "https://untrusted.example.com/image.png")
                        .param("create_at", "2000-01-01T00:00:00")
                        .cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(6))
                .andExpect(jsonPath("$.result.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.result.email").value("viewer@example.com"))
                .andExpect(jsonPath("$.result.displayName").value("New Viewer"))
                .andExpect(jsonPath("$.result.dob").value("2001-02-03"))
                .andExpect(jsonPath("$.result.create_at").value(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(user.getCreatedAt())))
                .andExpect(jsonPath("$.result.avatarUrl").value(startsWith(
                        "https://images.example.com/avatars/" + user.getId() + "/")));

        ProfileEntity saved = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals("New Viewer", saved.getDisplayName());
        assertEquals(LocalDate.of(2001, 2, 3), saved.getDob());
        assertTrue(saved.getAvatarUrl().endsWith(".png"));
        verify(storage).upload(anyString(), any(byte[].class), eq("image/png"));
        verify(storage).deleteManagedAvatar("https://images.example.com/old.png", user.getId());
    }

    @Test
    void updateMyProfilePreservesOmittedFields() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "New Viewer").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.dob").value("2000-01-01"))
                .andExpect(jsonPath("$.result.avatarUrl").value("https://images.example.com/old.png"));
        verifyNoInteractions(storage);
    }

    @Test
    void updateStreamerNameSynchronizesOnlyTheTrimmedName() throws Exception {
        UserEntity user = createProfile();
        StreamerEntity streamer = createStreamer(user);
        authenticate(user.getId().toString());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "  New streamer  ").cookie(accessCookie()))
                .andExpect(status().isOk());

        ProfileEntity saved = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals("New streamer", saved.getDisplayName());
        assertEquals("https://images.example.com/old.png", saved.getAvatarUrl());
        verify(channelClientResolver).updateChannelProfile(streamer.getId(), "New streamer", null);
        verifyNoInteractions(storage);
    }

    @Test
    void updateStreamerNameAndAvatarSynchronizesBothAndCommitsProfile() throws Exception {
        UserEntity user = createProfile();
        StreamerEntity streamer = createStreamer(user);
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").file(avatar())
                        .param("displayName", "  New streamer  ").cookie(accessCookie()))
                .andExpect(status().isOk());

        ProfileEntity saved = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals("New streamer", saved.getDisplayName());
        assertTrue(saved.getAvatarUrl().startsWith("https://images.example.com/avatars/" + user.getId() + "/"));
        verify(channelClientResolver).updateChannelProfile(streamer.getId(), "New streamer", saved.getAvatarUrl());
        verify(storage).deleteManagedAvatar("https://images.example.com/old.png", user.getId());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void updateStreamerDobDoesNotCallChannelService() throws Exception {
        UserEntity user = createProfile();
        createStreamer(user);
        authenticate(user.getId().toString());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("dob", "2001-02-03").cookie(accessCookie()))
                .andExpect(status().isOk());

        ProfileEntity saved = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals(LocalDate.of(2001, 2, 3), saved.getDob());
        assertEquals("Viewer", saved.getDisplayName());
        assertEquals("https://images.example.com/old.png", saved.getAvatarUrl());
        verifyNoInteractions(channelClientResolver, storage);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class,
            names = {"CHANNEL_SERVICE_UNAVAILABLE", "CHANNEL_NOT_FOUND", "CHANNEL_REQUEST_INVALID"})
    void channelSyncFailureRollsBackProfileAndDeletesOnlyTheNewUpload(ErrorCode errorCode) throws Exception {
        UserEntity user = createProfile();
        StreamerEntity streamer = createStreamer(user);
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
        doThrow(new AppException(errorCode)).when(channelClientResolver)
                .updateChannelProfile(eq(streamer.getId()), eq("New streamer"), anyString());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").file(avatar())
                        .param("displayName", "  New streamer  ").param("dob", "2001-02-03").cookie(accessCookie()))
                .andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));

        ProfileEntity unchanged = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals("Viewer", unchanged.getDisplayName());
        assertEquals(LocalDate.of(2000, 1, 1), unchanged.getDob());
        assertEquals("https://images.example.com/old.png", unchanged.getAvatarUrl());
        ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(objectKey.capture(), any(byte[].class), eq("image/png"));
        verify(storage).deleteQuietly(objectKey.getValue());
        verify(storage, never()).deleteManagedAvatar(any(), any());
    }

    @Test
    void updateMyProfileTrimsNamesBeforeApplyingTheLengthLimit() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "  " + "x".repeat(100) + "  ").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.displayName").value("x".repeat(100)));
    }

    @Test
    void updateMyProfileRejectsBlankDobEvenWhenOtherFieldsAreProvided() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "Changed").param("dob", "").cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.DOB_INVALID.getCode()));
        assertEquals("Viewer", profileRepository.findByUserId(user.getId()).orElseThrow().getDisplayName());
    }

    @Test
    void updateMyProfileRejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").param("displayName", "Viewer"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid"})
    void updateMyProfileRejectsInvalidTokenSubjects(String subject) throws Exception {
        authenticate(subject);
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "Viewer").cookie(accessCookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateMyProfileReturnsNotFoundForMissingProfile() throws Exception {
        authenticate(createUser("viewer").getId().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "Viewer").cookie(accessCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PROFILE_NOT_FOUND.getCode()));
    }

    @Test
    void updateMyProfileRejectsBannedAccountsWithoutUploading() throws Exception {
        UserEntity user = createProfile();
        user.setStatus(UserStatus.BANNED);
        userRepository.saveAndFlush(user);
        authenticate(user.getId().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").file(avatar()).cookie(accessCookie()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    void updateMyProfileRejectsBlankNames(String name) throws Exception {
        authenticate(UUID.randomUUID().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").param("displayName", name).cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.DISPLAY_NAME_INVALID.getCode()));
    }

    @Test
    void updateMyProfileRejectsLongNames() throws Exception {
        authenticate(UUID.randomUUID().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "x".repeat(101)).cookie(accessCookie()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0000-01-01", "2000-02-30", "2000-2-01", "not-a-date", "2000-01-01T00:00:00"})
    void updateMyProfileRejectsInvalidDates(String dob) throws Exception {
        authenticate(UUID.randomUUID().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").param("dob", dob).cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.DOB_INVALID.getCode()));
    }

    @Test
    void updateMyProfileRejectsFutureDates() throws Exception {
        authenticate(UUID.randomUUID().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("dob", LocalDate.now().plusDays(1).toString()).cookie(accessCookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMyProfileRejectsEmptyRequests() throws Exception {
        authenticate(UUID.randomUUID().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PROFILE_UPDATE_EMPTY.getCode()));
    }

    @Test
    void updateMyProfileRejectsFakeImages() throws Exception {
        authenticate(UUID.randomUUID().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .file(new MockMultipartFile("avatar", "fake.png", "image/png", "fake".getBytes()))
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.AVATAR_INVALID.getCode()));
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileRejectsEmptyFiles() throws Exception {
        authenticate(UUID.randomUUID().toString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .file(new MockMultipartFile("avatar", "empty.png", "image/png", new byte[0]))
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMyProfileParsesMultipartPatchOverHttp() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));

        HttpResponse<String> response = sendAvatarOverHttp(avatar().getBytes());

        assertEquals(200, response.statusCode());
        assertTrue(profileRepository.findByUserId(user.getId()).orElseThrow().getAvatarUrl()
                .startsWith("https://images.example.com/avatars/" + user.getId() + "/"));
        verify(storage).upload(anyString(), any(byte[].class), eq("image/png"));
    }

    @Test
    void updateMyProfileEnforcesServletUploadLimitOverHttp() throws Exception {
        authenticate(UUID.randomUUID().toString());

        HttpResponse<String> response = sendAvatarOverHttp(new byte[5 * 1024 * 1024 + 1]);

        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("\"code\":" + ErrorCode.AVATAR_TOO_LARGE.getCode()));
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileRejectsMalformedMultipartOverHttp() throws Exception {
        authenticate(UUID.randomUUID().toString());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users/me"))
                .header("Cookie", "access_token=valid-access-token")
                .header("Content-Type", "multipart/form-data")
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString("invalid multipart"))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(400, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileRollsBackTextChangesWhenUploadFails() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
        doThrow(new AppException(ErrorCode.AVATAR_UPLOAD_FAILED))
                .when(storage).upload(anyString(), any(byte[].class), anyString());
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .file(avatar()).param("displayName", "New Viewer").cookie(accessCookie()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(ErrorCode.AVATAR_UPLOAD_FAILED.getCode()));
        ProfileEntity saved = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals("Viewer", saved.getDisplayName());
        assertEquals("https://images.example.com/old.png", saved.getAvatarUrl());
        verify(storage).deleteQuietly(anyString());
        verify(storage, never()).deleteManagedAvatar(any(), any());
    }

    @Test
    void updateMyProfileCleansNewUploadOnActualTransactionRollback() throws Exception {
        UserEntity user = createProfile();
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setAvatar(avatar());

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            profileService.updateMyProfile(user.getId(), request);
            verify(storage, never()).deleteQuietly(anyString());
            verify(storage, never()).deleteManagedAvatar(any(), any());
            transaction.setRollbackOnly();
        });

        assertEquals("https://images.example.com/old.png", profileRepository.findByUserId(user.getId()).orElseThrow().getAvatarUrl());
        verify(storage).deleteQuietly(anyString());
        verify(storage, never()).deleteManagedAvatar(any(), any());
    }

    @Test
    void concurrentProfileUpdatesWaitForLockAndPreserveCommittedAvatar() throws Exception {
        UserEntity user = createProfile();
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
        CountDownLatch uploading = new CountDownLatch(1);
        CountDownLatch finishUpload = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            uploading.countDown();
            if (!finishUpload.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish upload");
            }
            return null;
        }).when(storage).upload(anyString(), any(byte[].class), anyString());
        UpdateProfileRequest first = new UpdateProfileRequest();
        first.setAvatar(avatar());
        UpdateProfileRequest second = new UpdateProfileRequest();
        second.setDisplayName("Concurrent Viewer");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstUpdate = executor.submit(() -> profileService.updateMyProfile(user.getId(), first));
            try {
                assertTrue(uploading.await(5, TimeUnit.SECONDS));
                var secondUpdate = executor.submit(() -> {
                    secondStarted.countDown();
                    return profileService.updateMyProfile(user.getId(), second);
                });
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> secondUpdate.get(200, TimeUnit.MILLISECONDS));
                finishUpload.countDown();
                String newUrl = firstUpdate.get(5, TimeUnit.SECONDS).getAvatarUrl();
                assertEquals(newUrl, secondUpdate.get(5, TimeUnit.SECONDS).getAvatarUrl());
                ProfileEntity saved = profileRepository.findByUserId(user.getId()).orElseThrow();
                assertEquals("Concurrent Viewer", saved.getDisplayName());
                assertEquals(newUrl, saved.getAvatarUrl());
            } finally {
                finishUpload.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2000-02-29", "1999-12-31"})
    void updateMyProfileAcceptsValidDobOnlyAndPreservesOtherFields(String dob) throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("dob", dob).cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.dob").value(dob))
                .andExpect(jsonPath("$.result.displayName").value("Viewer"))
                .andExpect(jsonPath("$.result.avatarUrl").value("https://images.example.com/old.png"));

        assertEquals(LocalDate.parse(dob), profileRepository.findByUserId(user.getId()).orElseThrow().getDob());
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "webp"})
    void updateMyProfileAcceptsEachSupportedImageFormatAndPreservesText(String format) throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
        byte[] content;
        if (format.equals("webp")) {
            content = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=");
        } else {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output);
            content = output.toByteArray();
        }

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .file(new MockMultipartFile("avatar", "../../fake.txt", "text/plain", content))
                        .cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.displayName").value("Viewer"))
                .andExpect(jsonPath("$.result.dob").value("2000-01-01"));

        String url = profileRepository.findByUserId(user.getId()).orElseThrow().getAvatarUrl();
        assertTrue(url.matches("https://images.example.com/avatars/" + user.getId() + "/[0-9a-f-]+\\." + format));
        String key = url.substring("https://images.example.com/".length());
        verify(storage).upload(eq(key), eq(content), eq(format.equals("jpg") ? "image/jpeg" : "image/" + format));
        verify(storage).deleteManagedAvatar("https://images.example.com/old.png", user.getId());
    }

    @Test
    void updateMyProfileUsesTokenOwnerAndLeavesAnotherProfileUnchanged() throws Exception {
        UserEntity owner = createProfile();
        UserEntity other = createUser("other");
        profileRepository.saveAndFlush(ProfileEntity.builder().user(other).displayName("Other")
                .avatarUrl("https://images.example.com/other.png").dob(LocalDate.of(1990, 1, 1)).build());
        authenticate(owner.getId().toString());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "Changed").param("userId", other.getId().toString())
                        .param("user.id", other.getId().toString()).param("user.email", "changed@example.com")
                        .param("id", other.getId().toString()).cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(owner.getId().toString()));

        assertEquals("Changed", profileRepository.findByUserId(owner.getId()).orElseThrow().getDisplayName());
        ProfileEntity unchanged = profileRepository.findByUserId(other.getId()).orElseThrow();
        assertEquals("Other", unchanged.getDisplayName());
        assertEquals("https://images.example.com/other.png", unchanged.getAvatarUrl());
        assertEquals(LocalDate.of(1990, 1, 1), unchanged.getDob());
        assertEquals("other@example.com", userRepository.findById(other.getId()).orElseThrow().getEmail());
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileAllowsFirstAvatarWithoutPreviousAvatarOrDob() throws Exception {
        UserEntity user = createUser("viewer");
        profileRepository.saveAndFlush(ProfileEntity.builder().user(user).displayName("Viewer").build());
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").file(avatar()).cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.dob").value(nullValue()));

        assertTrue(profileRepository.findByUserId(user.getId()).orElseThrow().getAvatarUrl()
                .startsWith("https://images.example.com/avatars/" + user.getId() + "/"));
        verify(storage).deleteManagedAvatar(null, user.getId());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void updateMyProfileRejectsInvalidAccessTokenWithoutChangingDatabase() throws Exception {
        UserEntity user = createProfile();
        when(jwtDecoder.decode("valid-access-token")).thenThrow(new BadJwtException("Invalid token"));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("displayName", "Changed").cookie(accessCookie()))
                .andExpect(status().isUnauthorized());

        assertEquals("Viewer", profileRepository.findByUserId(user.getId()).orElseThrow().getDisplayName());
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileRejectsEmptyAvatarWithoutSavingValidTextChanges() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .file(new MockMultipartFile("avatar", "empty.png", "image/png", new byte[0]))
                        .param("displayName", "Changed").param("dob", "2001-02-03").cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.AVATAR_INVALID.getCode()));

        ProfileEntity unchanged = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals("Viewer", unchanged.getDisplayName());
        assertEquals(LocalDate.of(2000, 1, 1), unchanged.getDob());
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileRejectsInvalidDisplayNameBeforeUploadingValidAvatar() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").file(avatar())
                        .param("displayName", "\u2003").cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.DISPLAY_NAME_INVALID.getCode()));

        assertEquals("https://images.example.com/old.png", profileRepository.findByUserId(user.getId()).orElseThrow().getAvatarUrl());
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileRejectsRequestsContainingOnlyProtectedFields() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me")
                        .param("email", "changed@example.com").param("avatarUrl", "https://other.example.com/avatar.png")
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PROFILE_UPDATE_EMPTY.getCode()));

        assertEquals("viewer@example.com", userRepository.findById(user.getId()).orElseThrow().getEmail());
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileRejectsJsonInsteadOfMultipart() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());

        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Changed\"}").cookie(accessCookie()))
                .andExpect(status().isUnsupportedMediaType());

        assertEquals("Viewer", profileRepository.findByUserId(user.getId()).orElseThrow().getDisplayName());
        verifyNoInteractions(storage);
    }

    @Test
    void updateMyProfileKeepsDatabaseCommitWhenOldAvatarDeletionFails() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
        doThrow(new IllegalStateException("Cleanup unavailable")).when(storage).deleteManagedAvatar(any(), any());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").file(avatar())
                        .param("displayName", "Changed").cookie(accessCookie()))
                .andExpect(status().isOk());

        ProfileEntity saved = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals("Changed", saved.getDisplayName());
        assertTrue(saved.getAvatarUrl().startsWith("https://images.example.com/avatars/" + user.getId() + "/"));
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void updateMyProfileCleansUploadedFileWhenDatabaseConstraintFailsOnFlush() throws Exception {
        UserEntity user = createProfile();
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
        UpdateProfileRequest request = UpdateProfileRequest.builder()
                .displayName("x".repeat(101)).dob(LocalDate.of(2001, 2, 3)).avatar(avatar()).build();

        assertThrows(DataIntegrityViolationException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    UserProfileResponse response = profileService.updateMyProfile(user.getId(), request);
                    assertTrue(response.getAvatarUrl().contains("/avatars/"));
                    verify(storage, never()).deleteManagedAvatar(any(), any());
                    profileRepository.flush();
                }));

        ProfileEntity unchanged = profileRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals("Viewer", unchanged.getDisplayName());
        assertEquals(LocalDate.of(2000, 1, 1), unchanged.getDob());
        assertEquals("https://images.example.com/old.png", unchanged.getAvatarUrl());
        verify(storage).deleteQuietly(anyString());
        verify(storage, never()).deleteManagedAvatar(any(), any());
    }

    @Test
    void repeatedAvatarUpdatesCreateUniqueKeysAndCleanEachPreviousAvatar() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").file(avatar()).cookie(accessCookie()))
                .andExpect(status().isOk());
        String firstUrl = profileRepository.findByUserId(user.getId()).orElseThrow().getAvatarUrl();
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/users/me").file(avatar()).cookie(accessCookie()))
                .andExpect(status().isOk());
        String secondUrl = profileRepository.findByUserId(user.getId()).orElseThrow().getAvatarUrl();

        assertNotEquals(firstUrl, secondUrl);
        verify(storage, times(2)).upload(anyString(), any(byte[].class), eq("image/png"));
        verify(storage).deleteManagedAvatar("https://images.example.com/old.png", user.getId());
        verify(storage).deleteManagedAvatar(firstUrl, user.getId());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void updateMyProfileEnforcesTotalRequestSizeWhenIndividualFilesAreWithinLimit() throws Exception {
        UserEntity user = createProfile();
        authenticate(user.getId().toString());
        String boundary = "profile-total-size-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String field : List.of("avatar", "extraFile")) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
                    + "\"; filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(new byte[4 * 1024 * 1024]);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users/me"))
                .header("Cookie", "access_token=valid-access-token")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode());
            assertTrue(response.body().contains("\"code\":" + ErrorCode.AVATAR_TOO_LARGE.getCode()));
        }

        assertEquals("https://images.example.com/old.png", profileRepository.findByUserId(user.getId()).orElseThrow().getAvatarUrl());
        verifyNoInteractions(storage);
    }

    private StreamerEntity createStreamer(UserEntity user) {
        return streamerRepository.saveAndFlush(StreamerEntity.builder().user(user).status(StreamerStatus.ACTIVE).build());
    }

    private UserEntity createProfile() {
        UserEntity user = createUser("viewer");
        profileRepository.saveAndFlush(ProfileEntity.builder().user(user).displayName("Viewer")
                .avatarUrl("https://images.example.com/old.png").dob(LocalDate.of(2000, 1, 1)).build());
        return user;
    }

    private MockMultipartFile avatar() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("avatar", "avatar.png", "image/png", output.toByteArray());
    }

    private HttpResponse<String> sendAvatarOverHttp(byte[] content) throws Exception {
        String boundary = "profile-test-boundary";
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"avatar\"; "
                + "filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users/me"))
                .header("Cookie", "access_token=valid-access-token")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArrays(List.of(prefix, content, suffix)))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private UserEntity createUser(String username) {
        return userRepository.saveAndFlush(UserEntity.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash("test-password-hash")
                .build());
    }

    private void authenticate(String subject) {
        Jwt.Builder jwt = Jwt.withTokenValue("valid-access-token")
                .header("alg", "HS512")
                .claim("scope", "ROLE_USER");
        if (subject != null) {
            jwt.subject(subject);
        }
        when(jwtDecoder.decode("valid-access-token")).thenReturn(jwt.build());
    }

    private Cookie accessCookie() {
        return new Cookie("access_token", "valid-access-token");
    }
}
