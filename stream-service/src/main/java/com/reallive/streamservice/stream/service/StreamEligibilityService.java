package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import java.util.UUID;

public interface StreamEligibilityService {
    /**
     * Synchronously resolves verified streamer, channel ownership and active-category context before DB writes.
     *
     * @param authenticatedUserId the user ID extracted from the trusted authenticated principal
     * @param categoryId the requested stream category identifier
     * @return validated identity and channel identifiers without provisioning media or issuing tokens
     * @throws AppException if identity/category is invalid, publishing is forbidden or an upstream service is unavailable
     */
    StreamPublishContext requirePublishContext(UUID authenticatedUserId, UUID categoryId);
}
