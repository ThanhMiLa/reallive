package com.reallive.channelservice.channel.client.streamer;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ActiveStreamerResponse {
    UUID streamerId;
}
