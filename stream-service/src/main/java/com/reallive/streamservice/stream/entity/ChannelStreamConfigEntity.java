package com.reallive.streamservice.stream.entity;

import com.reallive.streamservice.common.entity.BaseEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.data.domain.Persistable;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(
        name = "channel_stream_configs",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_channel_stream_configs_owner",
                    columnNames = "owner_user_id"),
            @UniqueConstraint(
                    name = "uk_channel_stream_configs_streamer",
                    columnNames = "streamer_id"),
            @UniqueConstraint(
                    name = "uk_channel_stream_configs_ingest_key",
                    columnNames = "current_ingest_key_id")
        })
public class ChannelStreamConfigEntity extends BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "channel_id", nullable = false, updatable = false)
    UUID channelId;

    @Column(name = "streamer_id", nullable = false, updatable = false)
    UUID streamerId;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    UUID ownerUserId;

    @Column(name = "title", nullable = false, length = 255)
    String title;

    @Column(name = "category_id")
    UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 30)
    StreamVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    ChannelStreamConfigStatus status;

    @Column(name = "current_ingest_key_id")
    UUID currentIngestKeyId;

    @Column(name = "blocked_reason", length = 500)
    String blockedReason;

    @Transient @Builder.Default boolean isNew = true;

    @Override
    public UUID getId() {
        return channelId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChannelStreamConfigEntity entity)) {
            return false;
        }
        return getChannelId() != null && getChannelId().equals(entity.getChannelId());
    }

    @Override
    public final int hashCode() {
        return ChannelStreamConfigEntity.class.hashCode();
    }
}
