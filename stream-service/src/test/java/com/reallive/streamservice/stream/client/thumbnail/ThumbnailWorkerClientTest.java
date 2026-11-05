package com.reallive.streamservice.stream.client.thumbnail;

import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThumbnailWorkerClientTest {

    private final ThumbnailWorkerApiClient api = mock(ThumbnailWorkerApiClient.class);
    private final ThumbnailProperties properties = new ThumbnailProperties();
    private final ThumbnailWorkerClient client = new ThumbnailWorkerClient(api, properties);

    @Test
    void sendsBatchWithConfiguredInternalTokenAndReturnsResponse() {
        ThumbnailJobPayload payload = payload();
        ThumbnailBatchResponse response = new ThumbnailBatchResponse(List.of(payload.jobId()), List.of());
        properties.setInternalToken("test-internal-token-0123456789abcdef");
        when(api.dispatchBatch(anyString(), any())).thenReturn(response);

        var result = client.dispatchBatch(List.of(payload));

        assertTrue(result.isPresent());
        assertEquals(response, result.orElseThrow());
        verify(api).dispatchBatch("test-internal-token-0123456789abcdef", new ThumbnailBatchRequest(List.of(payload)));
    }

    @Test
    void returnsEmptyWhenTheWorkerRejectsTheRequest() {
        when(api.dispatchBatch(anyString(), any())).thenThrow(httpError(503));

        assertTrue(client.dispatchBatch(List.of(payload())).isEmpty());
    }

    @Test
    void returnsEmptyWhenTheWorkerCannotBeReached() {
        when(api.dispatchBatch(anyString(), any())).thenThrow(new IllegalStateException("connection refused"));

        assertTrue(client.dispatchBatch(List.of(payload())).isEmpty());
    }

    @Test
    void returnsEmptyWhenTheResponseIsMissingAcceptedJobIds() {
        when(api.dispatchBatch(anyString(), any()))
                .thenReturn(new ThumbnailBatchResponse(null, null));
        assertTrue(client.dispatchBatch(List.of(payload())).isEmpty());

        when(api.dispatchBatch(anyString(), any())).thenReturn(null);
        assertTrue(client.dispatchBatch(List.of(payload())).isEmpty());
    }

    private ThumbnailJobPayload payload() {
        return new ThumbnailJobPayload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "http://media-server:8888/live/session/index.m3u8",
                "read-token",
                "2026-11-05T12:05:00Z",
                "2026-11-05T12:04:00Z"
        );
    }

    private FeignException httpError(int status) {
        Request request = Request.create(
                Request.HttpMethod.POST,
                "http://localhost/internal/api/thumbnail-jobs/batch",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null
        );
        Response response = Response.builder()
                .status(status)
                .reason("Worker error")
                .request(request)
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("ThumbnailWorkerApiClient#dispatchBatch", response);
    }
}
