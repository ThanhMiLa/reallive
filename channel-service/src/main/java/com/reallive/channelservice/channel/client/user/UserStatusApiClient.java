package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserStatusApiClient {

    UserStatusClient client;

    public UserStatusClient.Status getStatus(UUID userId) {
        try {
            var response = client.getStatus(userId);
            if (response == null || response.getCode() != 1000 || response.getResult() == null
                    || !userId.equals(response.getResult().userId()) || response.getResult().status() == null) {
                throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
            }
            return response.getResult().status();
        } catch (FeignException.NotFound exception) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        } catch (FeignException exception) {
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
    }
}
