package com.reallive.streamservice.stream.entity;

import com.reallive.streamservice.common.entity.BaseEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
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

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(
        name = "stream_sessions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_stream_sessions_playback_path", columnNames = "playback_path"),
                @UniqueConstraint(name = "uk_stream_sessions_owner_creation_request", columnNames = {"owner_user_id", "creation_request_id"})
        }
)
public class StreamSessionEntity extends BaseEntity implements Persistable<UUID> {

    @Id
    @Builder.Default
    @Column(name = "id", nullable = false, updatable = false)
    UUID id = UUID.randomUUID();

    @Column(name = "channel_id", nullable = false, updatable = false)
    UUID channelId;

    @Column(name = "streamer_id", nullable = false, updatable = false)
    UUID streamerId;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    UUID ownerUserId;

    @Column(name = "category_id", nullable = false)
    UUID categoryId;

    @Column(name = "title", nullable = false, length = 255)
    String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 30)
    StreamVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    StreamSessionStatus status;

    @Column(name = "playback_path", nullable = false, length = 500)
    String playbackPath;

    @Column(name = "creation_request_id", nullable = false, updatable = false)
    UUID creationRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_path_state", nullable = false, length = 30)
    MediaPathState mediaPathState;

    @Column(name = "media_source_id")
    UUID mediaSourceId;

    @Column(name = "media_source_type", length = 30)
    MediaSourceType mediaSourceType;

    @Column(name = "disconnected_at")
    LocalDateTime disconnectedAt;

    @Column(name = "reconnect_deadline_at")
    LocalDateTime reconnectDeadlineAt;

    @Column(name = "started_at")
    LocalDateTime startedAt;

    @Column(name = "ended_at")
    LocalDateTime endedAt;

    @Transient
    @Builder.Default
    boolean isNew = true;

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
        if (!(other instanceof StreamSessionEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return StreamSessionEntity.class.hashCode();
    }
}
