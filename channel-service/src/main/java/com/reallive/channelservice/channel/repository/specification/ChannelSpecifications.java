package com.reallive.channelservice.channel.repository.specification;

import com.reallive.channelservice.channel.entity.ChannelCategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelFollowerEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChannelSpecifications {

    public static Specification<ChannelEntity> isActive() {
        return (root, query, builder) -> builder.equal(root.get("status"), ChannelStatus.ACTIVE);
    }

    /**
     * Excludes the authenticated user's channels while retaining legacy channels with unknown owners.
     */
    public static Specification<ChannelEntity> notOwnedBy(UUID userId) {
        return (root, query, builder) -> builder.or(
                builder.isNull(root.get("userId")),
                builder.notEqual(root.get("userId"), userId));
    }

    public static Specification<ChannelEntity> inActiveCategories(Set<UUID> categoryIds) {
        if (categoryIds == null) {
            return Specification.unrestricted();
        }
        return (root, query, builder) -> {
            Subquery<UUID> membership = query.subquery(UUID.class);
            Root<ChannelCategoryEntity> association = membership.from(ChannelCategoryEntity.class);
            membership.select(association.get("id").get("channelId"))
                    .where(builder.equal(association.get("id").get("channelId"), root.get("id")),
                            association.get("id").get("categoryId").in(categoryIds),
                            builder.equal(association.get("category").get("status"), CategoryStatus.ACTIVE));
            return builder.exists(membership);
        };
    }

    /**
     * Filters current follow membership for the authenticated user.
     */
    public static Specification<ChannelEntity> matchesFollowing(UUID userId, boolean following) {
        return (root, query, builder) -> {
            Subquery<UUID> membership = query.subquery(UUID.class);
            Root<ChannelFollowerEntity> follower = membership.from(ChannelFollowerEntity.class);
            membership.select(follower.get("id").get("channelId"))
                    .where(builder.equal(follower.get("id").get("channelId"), root.get("id")),
                            builder.equal(follower.get("id").get("userId"), userId));
            return following ? builder.exists(membership) : builder.not(builder.exists(membership));
        };
    }

    /**
     * Matches literal search text across channel fields, using ! to escape LIKE wildcards.
     */
    public static Specification<ChannelEntity> matchesKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Specification.unrestricted();
        }
        String pattern = "%" + keyword.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return (root, query, builder) -> builder.or(
                builder.like(builder.lower(root.get("name")), pattern, '!'),
                builder.like(builder.lower(root.get("description")), pattern, '!'),
                builder.like(builder.lower(root.get("slug")), pattern, '!'));
    }
}
