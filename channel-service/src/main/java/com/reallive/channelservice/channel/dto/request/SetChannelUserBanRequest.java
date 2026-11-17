package com.reallive.channelservice.channel.dto.request;

import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import jakarta.validation.constraints.NotNull;

public record SetChannelUserBanRequest(
        @NotNull
        ChannelBanType banType
) {}
