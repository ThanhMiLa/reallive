package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserProfileClient {
    UserProfileApiClient api;

    public UserProfileResponse update(UUID userId, String displayName, MultipartFile avatar) {
        ApiResponse<UserProfileResponse> response;
        try {
            response = api.update(userId, displayName, avatar);
        } catch (FeignException exception) {
            throw new AppException(switch (exception.status()) {
                case 400, 413 -> ErrorCode.CHANNEL_REQUEST_INVALID;
                case 404 -> ErrorCode.USER_NOT_FOUND;
                default -> ErrorCode.USER_SERVICE_UNAVAILABLE;
            });
        }
        if (response == null || response.getStatus() != 200 || response.getCode() != 1000
                || response.getResult() == null
                || (displayName != null && (response.getResult().displayName() == null
                    || response.getResult().displayName().isBlank() || response.getResult().displayName().length() > 100))
                || (avatar != null && (response.getResult().avatarUrl() == null
                    || response.getResult().avatarUrl().isBlank() || response.getResult().avatarUrl().length() > 500))) {
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
        return response.getResult();
    }
}
