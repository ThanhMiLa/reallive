package com.reallive.streamservice.stream.entity.enums;

import lombok.Getter;

@Getter
public enum MediaSourceType {
    RTMP_CONN("rtmpConn"),
    RTMPS_CONN("rtmpsConn"),
    RTMP_SOURCE("rtmpSource");

    private final String value;

    MediaSourceType(String value) {
        this.value = value;
    }

    public boolean isPublisher() {
        return this == RTMP_CONN || this == RTMPS_CONN;
    }

    public static MediaSourceType fromValue(String value) {
        for (MediaSourceType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
