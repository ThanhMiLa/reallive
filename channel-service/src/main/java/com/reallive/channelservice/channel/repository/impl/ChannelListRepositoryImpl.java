package com.reallive.channelservice.channel.repository.impl;

import com.reallive.channelservice.channel.dto.request.FollowingSortBy;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelFollowerEntity;
import com.reallive.channelservice.channel.repository.ChannelListRepository;
import com.reallive.channelservice.channel.repository.specification.ChannelSpecifications;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(readOnly = true)
public class ChannelListRepositoryImpl implements ChannelListRepository {

    EntityManager entityManager;

    @Override
    public Page<ChannelEntity> findFollowingChannels(Specification<ChannelEntity> specification, UUID userId,
            FollowingSortBy sortBy, Sort.Direction direction, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<ChannelEntity> query = builder.createQuery(ChannelEntity.class);
        Root<ChannelEntity> channel = query.from(ChannelEntity.class);
        Predicate predicate = specification.toPredicate(channel, query, builder);
        Expression<?> sortExpression = channel.get("followerCount");
        if (sortBy == FollowingSortBy.FOLLOWED_AT) {
            Root<ChannelFollowerEntity> follower = query.from(ChannelFollowerEntity.class);
            predicate = builder.and(predicate,
                    builder.equal(follower.get("id").get("channelId"), channel.get("id")),
                    builder.equal(follower.get("id").get("userId"), userId));
            sortExpression = follower.get("followedAt");
        }
        query.select(channel).where(predicate).orderBy(
                direction.isAscending() ? builder.asc(sortExpression) : builder.desc(sortExpression),
                builder.desc(channel.get("createdAt")), builder.desc(channel.get("id")));
        return executePage(query, specification, pageable);
    }

    @Override
    public Page<ChannelEntity> findUnfollowedChannels(Specification<ChannelEntity> specification,
            Set<UUID> interestCategoryIds, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<ChannelEntity> query = builder.createQuery(ChannelEntity.class);
        Root<ChannelEntity> channel = query.from(ChannelEntity.class);
        List<Order> orders = new ArrayList<>();
        if (!interestCategoryIds.isEmpty()) {
            Predicate interestMatch = ChannelSpecifications.inActiveCategories(interestCategoryIds)
                    .toPredicate(channel, query, builder);
            orders.add(builder.asc(builder.<Integer>selectCase().when(interestMatch, 0).otherwise(1)));
        }
        orders.add(builder.desc(channel.get("followerCount")));
        orders.add(builder.desc(channel.get("createdAt")));
        orders.add(builder.desc(channel.get("id")));
        query.select(channel).where(specification.toPredicate(channel, query, builder)).orderBy(orders);
        return executePage(query, specification, pageable);
    }

    private Page<ChannelEntity> executePage(CriteriaQuery<ChannelEntity> contentQuery,
            Specification<ChannelEntity> specification, Pageable pageable) {
        List<ChannelEntity> content = entityManager.createQuery(contentQuery)
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize()).getResultList();
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<ChannelEntity> channel = countQuery.from(ChannelEntity.class);
        countQuery.select(builder.count(channel)).where(specification.toPredicate(channel, countQuery, builder));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }
}
