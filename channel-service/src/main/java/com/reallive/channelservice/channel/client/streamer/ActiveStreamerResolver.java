package com.reallive.channelservice.channel.client.streamer;

import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ActiveStreamerResolver {

    StreamerClient streamerClient;

    public UUID getActiveStreamerIdByUserId(UUID userId) {
        ResponseEntity<ApiResponse<ActiveStreamerResponse>> response;
        try {
            response = streamerClient.getActiveStreamerIdByUserId(userId);
        } catch (FeignException exception) {
            throw new AppException(switch (exception.status()) {
                case 404 -> ErrorCode.STREAMER_NOT_FOUND;
                case 403 -> ErrorCode.STREAMER_NOT_ACTIVE;
                default -> ErrorCode.USER_SERVICE_UNAVAILABLE;
            });
        }
        ApiResponse<ActiveStreamerResponse> body = response == null ? null : response.getBody();
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || body == null || body.getStatus() != 200 || body.getCode() != 1000
                || body.getResult() == null || body.getResult().getStreamerId() == null) {
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
        return body.getResult().getStreamerId();
    }
}
