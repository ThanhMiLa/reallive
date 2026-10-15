package com.reallive.channelservice.channel.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "channel_followers",
        indexes = @Index(name = "idx_channel_followers_user_id", columnList = "user_id"))
public class ChannelFollowerEntity {

    @EmbeddedId
    ChannelFollowerId id;

    @MapsId("channelId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_channel_followers_channel"))
    @ToString.Exclude
    ChannelEntity channel;

    @CreationTimestamp
    @Column(name = "followed_at", nullable = false, updatable = false)
    LocalDateTime followedAt;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChannelFollowerEntity entity)) {
            return false;
        }
        ChannelFollowerId key = getId();
        return key != null && key.getChannelId() != null && key.getUserId() != null
                && key.equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return ChannelFollowerEntity.class.hashCode();
    }
}
