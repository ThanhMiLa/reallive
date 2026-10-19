package com.reallive.channelservice.client.user;

import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import feign.FeignException;
import feign.codec.DecodeException;
import jakarta.validation.Validator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class  UserCategoryPreferenceResolver {

    UserClient client;
    Validator validator;

    public Set<UUID> getInterestCategoryIds(UUID userId) {
        ResponseEntity<ApiResponse<UserCategoryPreferencesResponse>> response;
        try {
            response = client.getCategoryPreferences(userId);
        } catch (DecodeException exception) {
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        } catch (FeignException exception) {
            if (exception.status() == 404) {
                throw new AppException(ErrorCode.USER_NOT_FOUND);
            }
            if (exception.status() < 0 || exception.status() >= 500) {
                log.warn("User category preferences unavailable (upstream status {}); using follower order",
                        exception.status());
                return Set.of();
            }
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
        ApiResponse<UserCategoryPreferencesResponse> body = response == null ? null : response.getBody();
        if (response == null || !response.getStatusCode().is2xxSuccessful() || body == null
                || body.getStatus() != 200 || body.getCode() != 1000 || body.getResult() == null
                || !validator.validate(body.getResult()).isEmpty()) {
            throw new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE);
        }
        return new LinkedHashSet<>(body.getResult().getCategoryIds());
    }
}
