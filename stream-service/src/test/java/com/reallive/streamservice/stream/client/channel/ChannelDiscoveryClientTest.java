package com.reallive.streamservice.stream.client.channel;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChannelDiscoveryClientTest {
    private final ChannelApiClient api = mock(ChannelApiClient.class);
    private final ChannelClient client = new ChannelClient(api);

    @Test
    void acceptsMissingIdsButRejectsMalformedAndFailedResponses() {
        UUID channel = UUID.randomUUID();
        var request = new LivestreamSummaries.Request(Set.of(channel), Set.of());
        when(api.getLivestreamSummaries(request)).thenReturn(ApiResponse.<LivestreamSummaries>builder()
                .status(200).code(1000).result(new LivestreamSummaries(List.of(), List.of())).build());
        assertTrue(client.getLivestreamSummaries(Set.of(channel), Set.of()).channels().isEmpty());
        when(api.getLivestreamSummaries(any())).thenReturn(ApiResponse.<LivestreamSummaries>builder()
                .status(200).code(1000).result(new LivestreamSummaries(null, List.of())).build());
        assertEquals(ErrorCode.STREAM_DATA_UNAVAILABLE, assertThrows(AppException.class,
                () -> client.getLivestreamSummaries(Set.of(channel), Set.of())).getErrorCode());
        when(api.getLivestreamSummaries(any())).thenThrow(new RuntimeException("unavailable"));
        assertEquals(ErrorCode.STREAM_DATA_UNAVAILABLE, assertThrows(AppException.class,
                () -> client.getLivestreamSummaries(Set.of(channel), Set.of())).getErrorCode());
    }
}
