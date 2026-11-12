package com.reallive.channelservice.channel.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "channel_user_bans")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChannelUserBanEntity {
    @Id UUID id;
    @Column(name = "channel_id", nullable = false)
    UUID channelId;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(nullable = false)
    String action;

    @Column(name = "ban_type", nullable = false)
    String banType;

    @Column(name = "performed_by", nullable = false)
    UUID performedBy;

    String reason;
    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;
}
