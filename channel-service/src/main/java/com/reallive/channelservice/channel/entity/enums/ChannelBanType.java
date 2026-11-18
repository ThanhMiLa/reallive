package com.reallive.channelservice.channel.entity.enums;

public enum ChannelBanType {
    CHAT_BAN,
    LIVE_VIEW_BAN,
    FULL_BAN;

    public String reason() {
        return switch (this) {
            case CHAT_BAN -> "Banned from channel chat";
            case LIVE_VIEW_BAN -> "Banned from channel livestream";
            case FULL_BAN -> "Banned from channel";
        };
    }
}
