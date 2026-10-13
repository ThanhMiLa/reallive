package com.reallive.channelservice.channel.dto.response;

import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChannelResponse {

    UUID id;
    UUID streamerId;
    String name;
    String slug;
    String description;
    String avatarUrl;
    String bannerUrl;
    ChannelStatus status;
    List<UUID> categoryIds;
}
