package com.reallive.streamservice.stream.entity.enums;

public enum StreamSessionStatus {
    READY,
    LIVE,
    RECONNECTING,
    ENDED;

    public boolean isActiveSlot() {
        return this != ENDED;
    }
}
