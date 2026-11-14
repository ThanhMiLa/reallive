package com.reallive.streamservice.stream.adapter.media.dto;

import java.util.UUID;

public record CcuMediaReader(
        UUID id,
        String path,
        String user
) {}
