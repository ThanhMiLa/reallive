package com.reallive.channelservice.channel.entity;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * Identifies a channel follow by the channel and the external user identifier.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Embeddable
public class ChannelFollowerId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "channel_id", nullable = false, updatable = false)
    UUID channelId;

    @Column(name = "user_id", nullable = false, updatable = false)
    UUID userId;
}
