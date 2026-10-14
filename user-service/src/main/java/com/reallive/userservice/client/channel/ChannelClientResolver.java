package com.reallive.userservice.client.channel;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class ChannelClientResolver {

    ChannelClient channelClient;
    ObjectMapper objectMapper;

    public boolean createChannel(CreateChannelRequest request) {
        ResponseEntity<ApiResponse<Boolean>> response;
        try {
            response = channelClient.createChannel(request);
        } catch (FeignException exception) {
            log.error("Channel creation failed for streamer {} (HTTP status {})",
                    request.getStreamerId(), exception.status(), exception);
            throw new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE);
        }
        ApiResponse<Boolean> body = response == null ? null : response.getBody();
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || body == null || body.getStatus() != 200 || body.getCode() != 1000
                || !Boolean.TRUE.equals(body.getResult())) {
            log.error("Channel creation returned an empty or unsuccessful response for streamer {}",
                    request.getStreamerId());
            throw new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE);
        }
        return true;
    }

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


    public boolean validateCategories(Set<UUID> categoryIds) {
        ValidateCategoriesRequest request = ValidateCategoriesRequest.builder()
                .categoryIds(categoryIds)
                .build();
        ResponseEntity<ApiResponse<Boolean>> response;
        try {
            response = channelClient.validateCategories(request);
        } catch (FeignException exception) {
            throw handleValidateCategoriesError(exception);
        }
        ApiResponse<Boolean> body = response == null ? null : response.getBody();
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || body == null || body.getStatus() != 200 || body.getCode() != 1000
                || !Boolean.TRUE.equals(body.getResult())) {
            throw new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE);
        }
        return true;
    }

    private AppException handleValidateCategoriesError(FeignException exception) {
        if (exception.status() == 400) {
            String content = exception.contentUTF8();
            if (content != null && !content.isBlank() && objectMapper != null) {
                try {
                    JsonNode node = objectMapper.readTree(content);
                    int code = node.path("code").asInt(-1);
                    if (code == 1011 || code == 1042) {
                        return new AppException(ErrorCode.CATEGORY_NOT_FOUND);
                    }
                    if (code == 1012 || code == 1043) {
                        return new AppException(ErrorCode.CATEGORY_NOT_ACTIVE);
                    }
                    String message = node.path("message").asText("");
                    if ("Category is not active".equalsIgnoreCase(message)) {
                        return new AppException(ErrorCode.CATEGORY_NOT_ACTIVE);
                    }
                    if ("Category not found".equalsIgnoreCase(message)) {
                        return new AppException(ErrorCode.CATEGORY_NOT_FOUND);
                    }
                } catch (Exception ignored) {
                    // Fall back to default bad request mapping
                }
            }
            return new AppException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        return new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE);
    }
}
