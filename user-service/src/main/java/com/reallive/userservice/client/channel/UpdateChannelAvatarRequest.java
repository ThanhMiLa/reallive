package com.reallive.userservice.client.channel;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateChannelAvatarRequest {
    String avatarUrl;
}
