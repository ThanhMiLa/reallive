package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.channel.client.user.UserStatusClient.IdentitySearchRequest;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserStatusApiClient {

    UserStatusClient client;

    public UserStatusClient.IdentityPage searchIdentities(String displayName, List<UUID> followerIds, int page, int size) {
        try {
            var response = client.searchIdentities(new IdentitySearchRequest(displayName, followerIds, page, size));
            var identities = response == null ? null : response.getResult();
            if (identities == null || response.getCode() != 1000 || identities.content() == null
                    || identities.page() != page || identities.size() != size || identities.content().size() > size
                    || identities.content().stream().anyMatch(identity -> identity == null || identity.userId() == null
                            || identity.displayName() == null || identity.status() == null)) {
                throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
            }
            return identities;
        } catch (FeignException exception) {
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
    }

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
