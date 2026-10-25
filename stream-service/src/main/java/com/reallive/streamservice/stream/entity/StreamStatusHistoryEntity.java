package com.reallive.streamservice.stream.entity;

import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "stream_status_history")
public class StreamStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @Column(name = "stream_id", nullable = false, updatable = false)
    UUID streamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30, updatable = false)
    StreamSessionStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, updatable = false)
    StreamSessionStatus status;

    @Column(name = "reason", length = 500, updatable = false)
    String reason;

    @Column(name = "media_source_id", updatable = false)
    UUID mediaSourceId;

    @Column(name = "media_event_id", updatable = false)
    UUID mediaEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StreamStatusHistoryEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return StreamStatusHistoryEntity.class.hashCode();
    }
}
