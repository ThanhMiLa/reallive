package com.reallive.channelservice.channel.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Sort;

import java.util.Set;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChannelListRequest {

    @Size(min = 1)
    Set<@NotNull UUID> categoryIds;

    @Size(max = 255)

    String keyword;

    @NotNull
    @Builder.Default
    Boolean following = false;

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
}
