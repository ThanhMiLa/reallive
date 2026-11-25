package com.reallive.paymentservice.client.stream;

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
public class StreamDonationContextApiClient {

    StreamDonationContextClient streamClient;

    public StreamDonationContextResponse getDonationContext(UUID sessionId) {
        ResponseEntity<ApiResponse<StreamDonationContextResponse>> response;
        try {
            response = streamClient.getDonationContext(sessionId);
        } catch (FeignException exception) {
            if (exception.status() == 404) {
                throw new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND);
            }
            log.warn("Stream donation context lookup failed with status {}", exception.status());
            throw new AppException(ErrorCode.STREAM_SERVICE_UNAVAILABLE);
        }
        if (response == null || response.getStatusCode().value() != 200) {
            log.warn("Stream donation context lookup returned invalid HTTP response");
            throw new AppException(ErrorCode.STREAM_SERVICE_UNAVAILABLE);
        }
        var body = response.getBody();
        if (body == null || body.getStatus() != 200 || body.getCode() != 1000 || body.getResult() == null) {
            log.warn("Stream donation context lookup returned invalid response envelope");
            throw new AppException(ErrorCode.STREAM_SERVICE_UNAVAILABLE);
        }
        var context = body.getResult();
        if (!sessionId.equals(context.sessionId()) || context.channelId() == null || context.streamerId() == null
                || context.ownerUserId() == null || context.visibility() == null || context.visibility().isBlank()
                || context.status() == null || context.status().isBlank()) {
            log.warn("Stream donation context lookup returned incomplete or mismatched context");
            throw new AppException(ErrorCode.STREAM_SERVICE_UNAVAILABLE);
        }
        return context;
    }
}
