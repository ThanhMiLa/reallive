package com.reallive.userservice.streamer.dto.response;

import com.reallive.userservice.streamer.entity.StreamerStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BecomeStreamerResponse {
    UUID streamerId;
    UUID userId;
    StreamerStatus status;
    Set<String> roles;
}
