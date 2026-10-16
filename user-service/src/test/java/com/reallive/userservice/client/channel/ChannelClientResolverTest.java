package com.reallive.userservice.client.channel;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ChannelClientResolverTest {

    private final ChannelClient client = mock(ChannelClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChannelClientResolver resolver = new ChannelClientResolver(client, mapper);
    private UUID streamerId;
    private static final String AVATAR_URL = "https://images.example.com/avatars/new.png";

    @BeforeEach
    void setUp() {
        streamerId = UUID.randomUUID();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = AVATAR_URL)
    void createChannelSendsRequestAndReturnsConfirmedSuccess(String avatarUrl) {
        UUID userId = UUID.randomUUID();
        CreateChannelRequest request = CreateChannelRequest.builder()
                .streamerId(streamerId).userId(userId).name("Test streamer").avatarUrl(avatarUrl).build();
        when(client.createChannel(request)).thenReturn(ResponseEntity.ok(envelope(200, 1000, true)));

        assertTrue(resolver.createChannel(request));
        assertEquals(userId.toString(), mapper.valueToTree(request).path("userId").asString());

        verify(client).createChannel(request);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 415, 500, 503})
    void createChannelLogsAndTranslatesHttpFailuresWithoutRetry(int status, CapturedOutput output) {
        Request httpRequest = Request.create(Request.HttpMethod.POST, "http://localhost/channel/internal/api/channels",
                Map.of(), null, StandardCharsets.UTF_8, null);
        FeignException remoteFailure = FeignException.errorStatus("ChannelClient#createChannel", Response.builder()
                .request(httpRequest).status(status).reason("Channel failure").headers(Map.of())
                .body("{\"code\":1004,\"message\":\"Invalid channel request\"}", StandardCharsets.UTF_8).build());
        when(client.createChannel(any())).thenThrow(remoteFailure);

        AppException failure = assertThrows(AppException.class, () -> resolver.createChannel(createChannelRequest()));

        assertEquals(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE, failure.getErrorCode());
        assertTrue(output.getOut().contains("HTTP status " + status));
        assertTrue(output.getOut().contains("Invalid channel request"));
        assertTrue(output.getOut().contains("feign.FeignException"));
        verify(client).createChannel(any(CreateChannelRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Connection refused", "Read timed out"})
    void createChannelLogsAndTranslatesConnectionFailuresWithoutRetry(String message, CapturedOutput output) {
        Request httpRequest = Request.create(Request.HttpMethod.POST, "http://localhost/channel/internal/api/channels",
                Map.of(), null, StandardCharsets.UTF_8, null);
        when(client.createChannel(any())).thenThrow(new RetryableException(-1, message,
                Request.HttpMethod.POST, (Long) null, httpRequest));

        AppException failure = assertThrows(AppException.class, () -> resolver.createChannel(createChannelRequest()));

        assertEquals(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE, failure.getErrorCode());
        assertTrue(output.getOut().contains("HTTP status -1"));
        assertTrue(output.getOut().contains(message));
        verify(client).createChannel(any(CreateChannelRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("unsuccessfulResponses")
    void createChannelRejectsEmptyMalformedOrUnsuccessfulResponses(
            ResponseEntity<ApiResponse<Boolean>> response, CapturedOutput output) {
        when(client.createChannel(any())).thenReturn(response);

        AppException failure = assertThrows(AppException.class, () -> resolver.createChannel(createChannelRequest()));

        assertEquals(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE, failure.getErrorCode());
        assertTrue(output.getOut().contains("empty or unsuccessful response"));
        verify(client).createChannel(any(CreateChannelRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "New name,https://images.example.com/avatars/new.png",
            "New name,NULL",
            "NULL,https://images.example.com/avatars/new.png",
            "NULL,NULL"
    }, nullValues = "NULL")
    void sendsOptionalNameAndAvatarAndReturnsConfirmedSuccess(String name, String avatarUrl) {
        when(client.updateChannelProfile(eq(streamerId), any(UpdateChannelProfileRequest.class)))
                .thenReturn(ResponseEntity.ok(envelope(200, 1000, true)));

        assertTrue(resolver.updateChannelProfile(streamerId, name, avatarUrl));

        ArgumentCaptor<UpdateChannelProfileRequest> request = ArgumentCaptor.forClass(UpdateChannelProfileRequest.class);
        verify(client).updateChannelProfile(eq(streamerId), request.capture());
        assertEquals(name, request.getValue().getName());
        assertEquals(avatarUrl, request.getValue().getAvatarUrl());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({
            "400,CHANNEL_REQUEST_INVALID",
            "404,CHANNEL_NOT_FOUND",
            "401,CHANNEL_SERVICE_UNAVAILABLE",
            "403,CHANNEL_SERVICE_UNAVAILABLE",
            "415,CHANNEL_SERVICE_UNAVAILABLE",
            "500,CHANNEL_SERVICE_UNAVAILABLE",
            "503,CHANNEL_SERVICE_UNAVAILABLE",
            "-1,CHANNEL_SERVICE_UNAVAILABLE"
    })
    void mapsEachRemoteFailureAndDoesNotRetry(int httpStatus, ErrorCode expectedError) {
        FeignException remoteFailure = mock(FeignException.class);
        when(remoteFailure.status()).thenReturn(httpStatus);
        when(client.updateChannelProfile(eq(streamerId), any(UpdateChannelProfileRequest.class)))
                .thenThrow(remoteFailure);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.updateChannelProfile(streamerId, "New name", AVATAR_URL));

        assertEquals(expectedError, failure.getErrorCode());
        verify(client).updateChannelProfile(eq(streamerId), any(UpdateChannelProfileRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("unsuccessfulResponses")
    void rejectsEmptyMalformedOrUnsuccessfulResponses(ResponseEntity<ApiResponse<Boolean>> response) {
        when(client.updateChannelProfile(eq(streamerId), any(UpdateChannelProfileRequest.class))).thenReturn(response);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.updateChannelProfile(streamerId, "New name", AVATAR_URL));

        assertEquals(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE, failure.getErrorCode());
        verify(client).updateChannelProfile(eq(streamerId), any(UpdateChannelProfileRequest.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void validateCategoriesReturnsConfirmedSuccess() {
        Set<UUID> categoryIds = Set.of(UUID.randomUUID(), UUID.randomUUID());
        when(client.validateCategories(any(ValidateCategoriesRequest.class)))
                .thenReturn(ResponseEntity.ok(envelope(200, 1000, true)));

        assertTrue(resolver.validateCategories(categoryIds));

        ArgumentCaptor<ValidateCategoriesRequest> captor = ArgumentCaptor.forClass(ValidateCategoriesRequest.class);
        verify(client).validateCategories(captor.capture());
        assertEquals(categoryIds, captor.getValue().getCategoryIds());
        verifyNoMoreInteractions(client);
    }

    @Test
    void validateCategoriesMaps400With1011ToCategoryNotFound() {
        FeignException remoteFailure = mock(FeignException.class);
        when(remoteFailure.status()).thenReturn(400);
        when(remoteFailure.contentUTF8()).thenReturn("{\"status\":400,\"code\":1011,\"message\":\"Category not found\"}");
        when(client.validateCategories(any(ValidateCategoriesRequest.class))).thenThrow(remoteFailure);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.validateCategories(Set.of(UUID.randomUUID())));

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, failure.getErrorCode());
        verify(client).validateCategories(any(ValidateCategoriesRequest.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void validateCategoriesMaps400With1012ToCategoryNotActive() {
        FeignException remoteFailure = mock(FeignException.class);
        when(remoteFailure.status()).thenReturn(400);
        when(remoteFailure.contentUTF8()).thenReturn("{\"status\":400,\"code\":1012,\"message\":\"Category is not active\"}");
        when(client.validateCategories(any(ValidateCategoriesRequest.class))).thenThrow(remoteFailure);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.validateCategories(Set.of(UUID.randomUUID())));

        assertEquals(ErrorCode.CATEGORY_NOT_ACTIVE, failure.getErrorCode());
        verify(client).validateCategories(any(ValidateCategoriesRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "invalid-json"})
    void validateCategoriesMaps400FallbackToCategoryNotFound(String body) {
        FeignException remoteFailure = mock(FeignException.class);
        when(remoteFailure.status()).thenReturn(400);
        when(remoteFailure.contentUTF8()).thenReturn(body);
        when(client.validateCategories(any(ValidateCategoriesRequest.class))).thenThrow(remoteFailure);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.validateCategories(Set.of(UUID.randomUUID())));

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, failure.getErrorCode());
        verify(client).validateCategories(any(ValidateCategoriesRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({
            "401,CHANNEL_SERVICE_UNAVAILABLE",
            "403,CHANNEL_SERVICE_UNAVAILABLE",
            "404,CHANNEL_SERVICE_UNAVAILABLE",
            "500,CHANNEL_SERVICE_UNAVAILABLE",
            "503,CHANNEL_SERVICE_UNAVAILABLE",
            "-1,CHANNEL_SERVICE_UNAVAILABLE"
    })
    void validateCategoriesMapsOtherHttpErrorsToChannelServiceUnavailable(int status, ErrorCode expectedError) {
        FeignException remoteFailure = mock(FeignException.class);
        when(remoteFailure.status()).thenReturn(status);
        when(client.validateCategories(any(ValidateCategoriesRequest.class))).thenThrow(remoteFailure);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.validateCategories(Set.of(UUID.randomUUID())));

        assertEquals(expectedError, failure.getErrorCode());
        verify(client).validateCategories(any(ValidateCategoriesRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("unsuccessfulResponses")
    void validateCategoriesRejectsEmptyMalformedOrUnsuccessfulResponses(ResponseEntity<ApiResponse<Boolean>> response) {
        when(client.validateCategories(any(ValidateCategoriesRequest.class))).thenReturn(response);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.validateCategories(Set.of(UUID.randomUUID())));

        assertEquals(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE, failure.getErrorCode());
        verify(client).validateCategories(any(ValidateCategoriesRequest.class));
        verifyNoMoreInteractions(client);
    }

    static Stream<ResponseEntity<ApiResponse<Boolean>>> unsuccessfulResponses() {
        return Stream.of(
                null,
                ResponseEntity.ok().build(),
                ResponseEntity.status(204).build(),
                ResponseEntity.status(503).body(envelope(200, 1000, true)),
                ResponseEntity.ok(envelope(400, 1000, true)),
                ResponseEntity.ok(envelope(200, 1004, true)),
                ResponseEntity.ok(envelope(200, 1000, null)),
                ResponseEntity.ok(envelope(200, 1000, false)),
                ResponseEntity.ok(new ApiResponse<>()));
    }

    private CreateChannelRequest createChannelRequest() {
        return CreateChannelRequest.builder().streamerId(streamerId).userId(UUID.randomUUID())
                .name("Test streamer").build();
    }

    private static ApiResponse<Boolean> envelope(int status, int code, Boolean result) {
        return ApiResponse.<Boolean>builder().status(status).code(code).result(result).build();
    }
}
