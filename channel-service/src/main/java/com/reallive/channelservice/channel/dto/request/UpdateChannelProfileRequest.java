package com.reallive.channelservice.channel.dto.request;

import com.reallive.channelservice.channel.dto.validation.HttpUrl;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateChannelProfileRequest {

    @Size(min = 1, max = 150)
    @Pattern(regexp = "(?sU).*\\S.*")
    String name;

    @Size(min = 1, max = 500)
    @HttpUrl
    String avatarUrl;
}
