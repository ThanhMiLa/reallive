package com.reallive.channelservice.channel.entity;

import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.common.entity.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

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
@Entity
@Table(name = "channels",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_channels_streamer_id", columnNames = "streamer_id"),
            @UniqueConstraint(name = "uk_channels_slug", columnNames = "slug")
        },
        indexes = {
            @Index(name = "idx_channels_name", columnList = "name"),
            @Index(name = "idx_channels_status", columnList = "status"),
            @Index(name = "idx_channels_deleted_at", columnList = "deleted_at")
        })
public class ChannelEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @Column(name = "streamer_id", nullable = false, updatable = false)
    UUID streamerId;

    @Column(name = "name", nullable = false, length = 150)
    String name;

    @Column(name = "slug", nullable = false, length = 150)
    String slug;

    @Column(name = "description", length = 1000)
    String description;

    @Column(name = "avatar_url", length = 500)
    String avatarUrl;

    @Column(name = "banner_url", length = 500)
    String bannerUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    ChannelStatus status;

    @Column(name = "deleted_at")
    LocalDateTime deletedAt;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChannelEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return ChannelEntity.class.hashCode();
    }
}
