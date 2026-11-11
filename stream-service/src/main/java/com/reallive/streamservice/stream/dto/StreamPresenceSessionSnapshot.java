package com.reallive.streamservice.stream.dto;

import java.util.UUID;

/**
 * Contains only the stream identity and owner needed to reconcile viewer presence.
 */
public record StreamPresenceSessionSnapshot(
        UUID id,
        UUID ownerUserId
) {}
