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
@Table(name = "channel_categories",
        indexes = @Index(name = "idx_channel_categories_category_id", columnList = "category_id"))
public class ChannelCategoryEntity {

    @EmbeddedId
    ChannelCategoryId id;

    @MapsId("channelId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_channel_categories_channel"))
    @ToString.Exclude
    ChannelEntity channel;

    @MapsId("categoryId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_channel_categories_category"))
    @ToString.Exclude
    CategoryEntity category;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChannelCategoryEntity entity)) {
            return false;
        }
        ChannelCategoryId key = getId();
        return key != null && key.getChannelId() != null && key.getCategoryId() != null
                && key.equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return ChannelCategoryEntity.class.hashCode();
    }
}
