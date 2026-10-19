package com.reallive.channelservice.channel.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Sort;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class FollowingChannelListRequest {

    @NotNull
    @Builder.Default
    FollowingSortBy sortBy = FollowingSortBy.FOLLOWER_COUNT;

    @NotNull
    @Builder.Default
    Sort.Direction sortDirection = Sort.Direction.DESC;

    @NotNull
    @Min(0)
    @Builder.Default
    Integer page = 0;

    @NotNull
    @Min(1)
    @Max(100)
    @Builder.Default
    Integer size = 20;

    @AssertTrue(message = "Page offset exceeds the supported range")
    public boolean isOffsetValid() {
        return page == null || size == null || (long) page * size <= Integer.MAX_VALUE;
    }
}
