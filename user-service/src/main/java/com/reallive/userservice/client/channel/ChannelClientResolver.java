package com.reallive.userservice.client.channel;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
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
public class ChannelClientResolver {

    ChannelClient channelClient;

    /**
     * Updates the supplied channel profile fields and validates Channel Service's success response.
     *
     * <p>Null fields preserve their current channel values. Supplied values must not be empty
     * or blank; validation is performed by Channel Service.</p>
     *
     * @param streamerId the streamer ID owning the channel
     * @param name the new channel name, or {@code null} to preserve it
     * @param avatarUrl the new profile avatar URL, or {@code null} to preserve it
     * @return {@code true} when Channel Service confirms the update
     * @throws AppException if the request is invalid, the channel is missing, or Channel Service
     *                      is unavailable or returns an unsuccessful response
     */
    public boolean updateChannelProfile(UUID streamerId, String name, String avatarUrl) {
        UpdateChannelProfileRequest request = UpdateChannelProfileRequest.builder()
                .name(name)
                .avatarUrl(avatarUrl)
                .build();
        ResponseEntity<ApiResponse<Boolean>> response;
        try {
            response = channelClient.updateChannelProfile(streamerId, request);
        } catch (FeignException exception) {
            throw new AppException(switch (exception.status()) {
                case 400 -> ErrorCode.CHANNEL_REQUEST_INVALID;
                case 404 -> ErrorCode.CHANNEL_NOT_FOUND;
                default -> ErrorCode.CHANNEL_SERVICE_UNAVAILABLE;
            });
        }
        ApiResponse<Boolean> body = response == null ? null : response.getBody();
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || body == null || body.getStatus() != 200 || body.getCode() != 1000
                || !Boolean.TRUE.equals(body.getResult())) {
            throw new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE);
        }
        return true;
    }
}
