package com.reallive.userservice.client.channel;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

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

class ChannelClientResolverTest {

    private final ChannelClient client = mock(ChannelClient.class);
    private final ChannelClientResolver resolver = new ChannelClientResolver(client);
    private UUID streamerId;
    private static final String AVATAR_URL = "https://images.example.com/avatars/new.png";

    @BeforeEach
    void setUp() {
        streamerId = UUID.randomUUID();
    }

    @Test
    void sendsTheStreamerIdAndAvatarUrlAndReturnsConfirmedSuccess() {
        when(client.updateChannelAvatar(eq(streamerId), any(UpdateChannelAvatarRequest.class)))
                .thenReturn(ResponseEntity.ok(envelope(200, 1000, true)));

        assertTrue(resolver.updateChannelAvatar(streamerId, AVATAR_URL));

        ArgumentCaptor<UpdateChannelAvatarRequest> request = ArgumentCaptor.forClass(UpdateChannelAvatarRequest.class);
        verify(client).updateChannelAvatar(eq(streamerId), request.capture());
        assertEquals(AVATAR_URL, request.getValue().getAvatarUrl());
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
        when(client.updateChannelAvatar(eq(streamerId), any(UpdateChannelAvatarRequest.class)))
                .thenThrow(remoteFailure);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.updateChannelAvatar(streamerId, AVATAR_URL));

        assertEquals(expectedError, failure.getErrorCode());
        verify(client).updateChannelAvatar(eq(streamerId), any(UpdateChannelAvatarRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("unsuccessfulResponses")
    void rejectsEmptyMalformedOrUnsuccessfulResponses(ResponseEntity<ApiResponse<Boolean>> response) {
        when(client.updateChannelAvatar(eq(streamerId), any(UpdateChannelAvatarRequest.class))).thenReturn(response);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.updateChannelAvatar(streamerId, AVATAR_URL));

        assertEquals(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE, failure.getErrorCode());
        verify(client).updateChannelAvatar(eq(streamerId), any(UpdateChannelAvatarRequest.class));
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

    private static ApiResponse<Boolean> envelope(int status, int code, Boolean result) {
        return ApiResponse.<Boolean>builder().status(status).code(code).result(result).build();
    }
}
