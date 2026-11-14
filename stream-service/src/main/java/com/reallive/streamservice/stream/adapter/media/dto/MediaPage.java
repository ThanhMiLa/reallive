package com.reallive.streamservice.stream.adapter.media.dto;

import java.util.List;

public record MediaPage<T>(
        int itemCount,
        int pageCount,
        List<T> items
) {}
