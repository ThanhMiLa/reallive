package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.List;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserChatProfileApiClient {
    UserChatProfileClient client;

    public List<UserChatProfileClient.ChatProfile> profiles(List<UUID> userIds) {
        try {
            var response = client.profiles(new UserChatProfileClient.BatchRequest(userIds));
            if (response == null || response.getCode() != 1000 || response.getResult() == null)
                throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
            return response.getResult();
        } catch (FeignException exception) {
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
    }

    public UserChatProfileClient.ChatProfile profile(UUID userId) {
        try {
            var response = client.profile(userId);
            if (response == null || response.getCode() != 1000 || response.getResult() == null
                    || !userId.equals(response.getResult().userId())) {
                throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
            }
            return response.getResult();
        } catch (FeignException exception) {
            throw new AppException(exception.status() == 404 ? ErrorCode.USER_NOT_FOUND : ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
    }
}
