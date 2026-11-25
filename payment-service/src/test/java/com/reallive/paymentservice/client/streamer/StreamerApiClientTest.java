package com.reallive.paymentservice.client.streamer;

import com.reallive.paymentservice.common.dto.ApiResponse;
import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StreamerApiClientTest {

    private final StreamerClient streamerClient = mock(StreamerClient.class);
    private final StreamerApiClient apiClient = new StreamerApiClient(streamerClient);

    @Test
    void returnsStreamerIdWhenResponseEnvelopeIsValid() {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        when(streamerClient.getActiveStreamerIdByUserId(userId))
                .thenReturn(ResponseEntity.ok(envelope(200, 1000, new ActiveStreamerResponse(streamerId))));

        assertEquals(streamerId, apiClient.getActiveStreamerIdByUserId(userId));
        verify(streamerClient).getActiveStreamerIdByUserId(userId);
        verifyNoMoreInteractions(streamerClient);
    }

    @ParameterizedTest
    @CsvSource({
            "404,STREAMER_NOT_FOUND",
            "403,STREAMER_NOT_ACTIVE",
            "400,USER_SERVICE_UNAVAILABLE",
            "401,USER_SERVICE_UNAVAILABLE",
            "500,USER_SERVICE_UNAVAILABLE",
            "503,USER_SERVICE_UNAVAILABLE",
            "-1,USER_SERVICE_UNAVAILABLE"
    })
    void mapsFeignExceptionsToExpectedErrorCode(int httpStatus, ErrorCode expectedError) {
        UUID userId = UUID.randomUUID();
        FeignException failure = mock(FeignException.class);
        when(failure.status()).thenReturn(httpStatus);
        when(streamerClient.getActiveStreamerIdByUserId(userId)).thenThrow(failure);

        AppException exception = assertThrows(AppException.class,
                () -> apiClient.getActiveStreamerIdByUserId(userId));

        assertEquals(expectedError, exception.getErrorCode());
        verify(streamerClient).getActiveStreamerIdByUserId(userId);
        verifyNoMoreInteractions(streamerClient);
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsNullNon2xxAndMalformedEnvelopesAsUserServiceUnavailable(
            ResponseEntity<ApiResponse<ActiveStreamerResponse>> response) {
        UUID userId = UUID.randomUUID();
        when(streamerClient.getActiveStreamerIdByUserId(userId)).thenReturn(response);

        AppException exception = assertThrows(AppException.class,
                () -> apiClient.getActiveStreamerIdByUserId(userId));

        assertEquals(ErrorCode.USER_SERVICE_UNAVAILABLE, exception.getErrorCode());
        verify(streamerClient).getActiveStreamerIdByUserId(userId);
        verifyNoMoreInteractions(streamerClient);
    }

    static Stream<ResponseEntity<ApiResponse<ActiveStreamerResponse>>> invalidResponses() {
        UUID streamerId = UUID.randomUUID();
        return Stream.of(
                null,
                ResponseEntity.status(302).body(envelope(200, 1000, new ActiveStreamerResponse(streamerId))),
                ResponseEntity.status(503).body(envelope(200, 1000, new ActiveStreamerResponse(streamerId))),
                ResponseEntity.ok().build(),
                ResponseEntity.status(204).build(),
                ResponseEntity.ok(envelope(500, 1000, new ActiveStreamerResponse(streamerId))),
                ResponseEntity.ok(envelope(200, 9999, new ActiveStreamerResponse(streamerId))),
                ResponseEntity.ok(envelope(200, 1000, null)),
                ResponseEntity.ok(envelope(200, 1000, new ActiveStreamerResponse(null)))
        );
    }

    private static ApiResponse<ActiveStreamerResponse> envelope(
            int status, int code, ActiveStreamerResponse result) {
        return ApiResponse.<ActiveStreamerResponse>builder()
                .status(status)
                .code(code)
                .result(result)
                .build();
    }
}
