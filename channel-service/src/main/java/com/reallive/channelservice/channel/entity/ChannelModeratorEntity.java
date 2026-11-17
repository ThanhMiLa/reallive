package com.reallive.channelservice.channel.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "channel_moderators")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChannelModeratorEntity {
    @EmbeddedId ChannelModeratorId id;
    @CreationTimestamp
    @Column(name = "assigned_at", nullable = false, updatable = false)
    LocalDateTime assignedAt;
}
