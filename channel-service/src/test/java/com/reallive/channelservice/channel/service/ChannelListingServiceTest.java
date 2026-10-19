package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.request.FollowingChannelListRequest;
import com.reallive.channelservice.channel.dto.request.FollowingSortBy;
import com.reallive.channelservice.channel.dto.request.UnfollowedChannelListRequest;
import com.reallive.channelservice.channel.dto.response.ChannelListResponse;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.mapper.ChannelMapper;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.impl.ChannelServiceImpl;
import com.reallive.channelservice.client.user.UserCategoryPreferenceResolver;
import com.reallive.channelservice.common.dto.PageResponse;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelListingServiceTest {

    private static final UUID USER = UUID.randomUUID();
    @Mock
    private ChannelRepository channels;
    @Mock
    private ChannelMapper mapper;
    @Mock
    private UserCategoryPreferenceResolver interests;
    @InjectMocks
    private ChannelServiceImpl service;

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true"})
    void explicitFiltersNeverReadPreferences(boolean keyword, boolean categories) {
        UnfollowedChannelListRequest request = UnfollowedChannelListRequest.builder()
                .keyword(keyword ? "stream" : null)
                .categoryIds(categories ? Set.of(UUID.randomUUID()) : null).build();
        when(channels.findUnfollowedChannels(any(), eq(Set.of()), any())).thenReturn(Page.empty());

        service.getUnfollowedChannels(USER, request);

        verify(channels).findUnfollowedChannels(any(), eq(Set.of()), eq(PageRequest.of(0, 20)));
        verifyNoInteractions(interests);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\u2003"})
    void noFiltersReadCurrentPreferencesOnceBeforeQueryingChannels(String keyword) {
        Set<UUID> categoryIds = Set.of(UUID.randomUUID());
        when(interests.getInterestCategoryIds(USER)).thenReturn(categoryIds);
        when(channels.findUnfollowedChannels(any(), eq(categoryIds), any())).thenReturn(Page.empty());

        service.getUnfollowedChannels(USER, UnfollowedChannelListRequest.builder().keyword(keyword).build());

        var order = inOrder(interests, channels);
        order.verify(interests).getInterestCategoryIds(USER);
        order.verify(channels).findUnfollowedChannels(any(), eq(categoryIds), eq(PageRequest.of(0, 20)));
        verifyNoMoreInteractions(interests);
    }

    @Test
    void preferenceContractErrorsPropagateBeforeQueryingDatabase() {
        when(interests.getInterestCategoryIds(USER)).thenThrow(new AppException(ErrorCode.USER_NOT_FOUND));

        assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(AppException.class,
                () -> service.getUnfollowedChannels(USER, new UnfollowedChannelListRequest())).getErrorCode());
        verifyNoInteractions(channels, mapper);
    }

    @ParameterizedTest
    @CsvSource({"FOLLOWER_COUNT,ASC", "FOLLOWER_COUNT,DESC", "FOLLOWED_AT,ASC", "FOLLOWED_AT,DESC"})
    void followingPassesOneSortCriterionAndPreservesPageMetadata(FollowingSortBy sortBy, Sort.Direction direction) {
        ChannelEntity entity = ChannelEntity.builder().id(UUID.randomUUID()).followerCount(3_000_000_000L).build();
        ChannelListResponse summary = ChannelListResponse.builder().id(entity.getId())
                .numberOfFollower(entity.getFollowerCount()).build();
        PageRequest page = PageRequest.of(2, 5);
        when(channels.findFollowingChannels(any(), eq(USER), eq(sortBy), eq(direction), eq(page)))
                .thenReturn(new PageImpl<>(List.of(entity), page, 11));
        when(mapper.toListResponse(entity)).thenReturn(summary);

        PageResponse<ChannelListResponse> response = service.getFollowingChannels(USER,
                FollowingChannelListRequest.builder().sortBy(sortBy).sortDirection(direction).page(2).size(5).build());

        assertEquals(List.of(summary), response.getContent());
        assertEquals(2, response.getPage());
        assertEquals(5, response.getSize());
        assertEquals(11, response.getTotalElements());
        assertEquals(3, response.getTotalPages());
        verifyNoInteractions(interests);
    }

    @Test
    void bothServicesRejectMissingIdentityWithoutCallingDependencies() {
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(AppException.class,
                () -> service.getFollowingChannels(null, new FollowingChannelListRequest())).getErrorCode());
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(AppException.class,
                () -> service.getUnfollowedChannels(null, new UnfollowedChannelListRequest())).getErrorCode());
        verifyNoInteractions(channels, interests, mapper);
    }
}
