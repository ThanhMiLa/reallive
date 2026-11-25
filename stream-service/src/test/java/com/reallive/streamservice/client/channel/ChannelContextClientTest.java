package com.reallive.streamservice.client.channel;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelContextClientTest {

    private final ChannelApiClient api = mock(ChannelApiClient.class);
    private final ChannelClient client = new ChannelClient(api);

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "BANNED"})
    void rejectsRecognizedInactiveStatusesAsIneligible(String status) {
        UUID streamerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(api.getContext(streamerId)).thenReturn(ApiResponse.<StreamingChannelContext>builder()
                .status(200).code(1000)
                .result(new StreamingChannelContext(UUID.randomUUID(), streamerId, ownerId, status)).build());

        AppException exception = assertThrows(AppException.class, () -> client.requireContext(streamerId, ownerId));

        assertEquals(ErrorCode.STREAM_CHANNEL_NOT_ELIGIBLE, exception.getErrorCode());
    }
}
