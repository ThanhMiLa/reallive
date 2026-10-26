package com.reallive.streamservice.stream.client.user;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.client.ClientResponses;
import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserClient {
    UserApiClient api;

    public StreamingEligibility requireEligibility(UUID userId) {
        StreamingEligibility result = ClientResponses.read(() -> api.getEligibility(userId),
                ErrorCode.STREAMER_NOT_ELIGIBLE, Set.of(403, 404));
        if (!userId.equals(result.userId()) || result.streamerId() == null) {
            throw new AppException(ErrorCode.STREAM_DATA_UNAVAILABLE);
        }
        return result;
    }
}
