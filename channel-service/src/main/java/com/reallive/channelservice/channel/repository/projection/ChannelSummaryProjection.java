package com.reallive.channelservice.channel.repository.projection;

public interface ChannelSummaryProjection {

    String getName();

    String getBannerUrl();

    String getAvatarUrl();

    long getFollowerCount();
}
