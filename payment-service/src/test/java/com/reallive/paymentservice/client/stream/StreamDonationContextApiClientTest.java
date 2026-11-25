package com.reallive.paymentservice.client.stream;

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
import static org.mockito.Mockito.when;

class StreamDonationContextApiClientTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private final StreamDonationContextClient client = mock(StreamDonationContextClient.class);
    private final StreamDonationContextApiClient apiClient = new StreamDonationContextApiClient(client);

    @Test
    void returnsCompleteContext() {
        var context = context(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PUBLIC", "LIVE");
        when(client.getDonationContext(SESSION_ID)).thenReturn(ResponseEntity.ok(envelope(200, 1000, context)));
        assertEquals(context, apiClient.getDonationContext(SESSION_ID));
    }

    @ParameterizedTest
    @CsvSource({"404,STREAM_SESSION_NOT_FOUND", "403,STREAM_SERVICE_UNAVAILABLE",
            "401,STREAM_SERVICE_UNAVAILABLE", "500,STREAM_SERVICE_UNAVAILABLE",
            "503,STREAM_SERVICE_UNAVAILABLE", "-1,STREAM_SERVICE_UNAVAILABLE"})
    void mapsHttpAndNetworkFailures(int status, ErrorCode expected) {
        var failure = mock(FeignException.class);
        when(failure.status()).thenReturn(status);
        when(client.getDonationContext(SESSION_ID)).thenThrow(failure);
        assertEquals(expected, assertThrows(AppException.class,
                () -> apiClient.getDonationContext(SESSION_ID)).getErrorCode());
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsInvalidEnvelopesAndMissingRequiredFields(ResponseEntity<ApiResponse<StreamDonationContextResponse>> response) {
        when(client.getDonationContext(SESSION_ID)).thenReturn(response);
        assertEquals(ErrorCode.STREAM_SERVICE_UNAVAILABLE, assertThrows(AppException.class,
                () -> apiClient.getDonationContext(SESSION_ID)).getErrorCode());
    }

    static Stream<ResponseEntity<ApiResponse<StreamDonationContextResponse>>> invalidResponses() {
        UUID id = UUID.randomUUID();
        var valid = context(SESSION_ID, id, id, id, "PUBLIC", "LIVE");
        return Stream.of(null, ResponseEntity.status(302).body(envelope(200, 1000, valid)),
                ResponseEntity.status(503).body(envelope(200, 1000, valid)), ResponseEntity.ok().build(),
                ResponseEntity.ok(envelope(500, 1000, valid)), ResponseEntity.ok(envelope(200, 0, valid)),
                ResponseEntity.ok(envelope(200, 1000, null)),
                ResponseEntity.ok(envelope(200, 1000, context(null, id, id, id, "PUBLIC", "LIVE"))),
                ResponseEntity.ok(envelope(200, 1000, context(id, id, id, id, "PUBLIC", "LIVE"))),
                ResponseEntity.ok(envelope(200, 1000, context(SESSION_ID, null, id, id, "PUBLIC", "LIVE"))),
                ResponseEntity.ok(envelope(200, 1000, context(SESSION_ID, id, null, id, "PUBLIC", "LIVE"))),
                ResponseEntity.ok(envelope(200, 1000, context(SESSION_ID, id, id, null, "PUBLIC", "LIVE"))),
                ResponseEntity.ok(envelope(200, 1000, context(SESSION_ID, id, id, id, null, "LIVE"))),
                ResponseEntity.ok(envelope(200, 1000, context(SESSION_ID, id, id, id, " ", "LIVE"))),
                ResponseEntity.ok(envelope(200, 1000, context(SESSION_ID, id, id, id, "PUBLIC", null))),
                ResponseEntity.ok(envelope(200, 1000, context(SESSION_ID, id, id, id, "PUBLIC", " "))));
    }

    private static StreamDonationContextResponse context(
            UUID sessionId, UUID channelId, UUID streamerId, UUID ownerId, String visibility, String status) {
        return new StreamDonationContextResponse(sessionId, channelId, streamerId, ownerId, visibility, status);
    }

    private static ApiResponse<StreamDonationContextResponse> envelope(int status, int code, StreamDonationContextResponse result) {
        return ApiResponse.<StreamDonationContextResponse>builder().status(status).code(code).result(result).build();
    }
}
