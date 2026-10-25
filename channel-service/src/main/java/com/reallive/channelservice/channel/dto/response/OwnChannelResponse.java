package com.reallive.channelservice.channel.dto.response;

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
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class OwnChannelResponse {

    UUID id;
    UUID streamerId;
    String name;
    String slug;
    String description;
    String avatarUrl;
    String bannerUrl;
    String status;
    List<CategorySummaryResponse> categories;
    long numberOfFollower;
}
