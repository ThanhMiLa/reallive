package com.reallive.streamservice.client.user;

import com.reallive.streamservice.client.ClientResponses;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserClient {
    static final int PROFILE_BATCH_SIZE = 50;

    UserApiClient api;

    public StreamingEligibility requireEligibility(UUID userId) {
        StreamingEligibility result = ClientResponses.read(() -> api.getEligibility(userId),
                ErrorCode.STREAMER_NOT_ELIGIBLE, Set.of(403, 404));
        if (!userId.equals(result.userId()) || result.streamerId() == null) {
            throw new AppException(ErrorCode.STREAM_DATA_UNAVAILABLE);
        }
        return result;
    }

    public Map<UUID, ViewerProfile> getViewerProfiles(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> uniqueUserIds = new ArrayList<>(new LinkedHashSet<>(
                userIds.stream().filter(Objects::nonNull).toList()));
        if (uniqueUserIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ViewerProfile> profiles = new LinkedHashMap<>(uniqueUserIds.size());
        for (int offset = 0; offset < uniqueUserIds.size(); offset += PROFILE_BATCH_SIZE) {
            List<UUID> batch = List.copyOf(uniqueUserIds.subList(
                    offset, Math.min(offset + PROFILE_BATCH_SIZE, uniqueUserIds.size())));
            Set<UUID> requestedBatch = Set.copyOf(batch);
            List<ViewerProfile> response = ClientResponses.read(
                    () -> api.getViewerProfiles(new UserApiClient.ViewerProfileBatchRequest(batch)),
                    ErrorCode.STREAM_DATA_UNAVAILABLE,
                    Set.of());
            for (ViewerProfile profile : response) {
                if (profile == null
                        || profile.userId() == null
                        || profile.displayName() == null
                        || !requestedBatch.contains(profile.userId())) {
                    throw new AppException(ErrorCode.STREAM_DATA_UNAVAILABLE);
                }
                profiles.put(profile.userId(), profile);
            }
        }
        return Map.copyOf(profiles);
    }
}
