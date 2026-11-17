package com.reallive.channelservice.channel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import lombok.experimental.FieldDefaults;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChannelModeratorId implements Serializable {
    @Column(name = "channel_id", nullable = false) UUID channelId;
    @Column(name = "user_id", nullable = false) UUID userId;
}
