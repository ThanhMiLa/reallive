package com.reallive.paymentservice.client.streamer;

import com.reallive.paymentservice.common.dto.ApiResponse;
import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamerApiClient {

    StreamerClient streamerClient;

    public UUID getActiveStreamerIdByUserId(UUID userId) {
        ResponseEntity<ApiResponse<ActiveStreamerResponse>> response;
        try {
            response = streamerClient.getActiveStreamerIdByUserId(userId);
        } catch (FeignException exception) {
            ErrorCode errorCode = switch (exception.status()) {
                case 404 -> ErrorCode.STREAMER_NOT_FOUND;
                case 403 -> ErrorCode.STREAMER_NOT_ACTIVE;
                default -> {
                    log.warn("User service active streamer lookup failed with status {}", exception.status());
                    yield ErrorCode.USER_SERVICE_UNAVAILABLE;
                }
            };
            throw new AppException(errorCode);
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.warn("User service active streamer lookup returned invalid HTTP response");
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
        ApiResponse<ActiveStreamerResponse> body = response.getBody();
        if (body == null
                || body.getStatus() != 200
                || body.getCode() != 1000
                || body.getResult() == null
                || body.getResult().streamerId() == null) {
            log.warn("User service active streamer lookup returned invalid response envelope");
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
        return body.getResult().streamerId();
    }
}
