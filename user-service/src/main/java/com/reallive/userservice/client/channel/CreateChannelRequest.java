package com.reallive.userservice.client.channel;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CreateChannelRequest {
    UUID streamerId;
    UUID userId;
    String name;
    String avatarUrl;
}
