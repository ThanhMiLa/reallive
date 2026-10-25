package com.reallive.streamservice.stream.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum MediaSourceType {
    RTMP_CONN("rtmpConn"),
    RTMPS_CONN("rtmpsConn");

    private final String value;

    public static MediaSourceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
    }
}
