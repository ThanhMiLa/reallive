package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.dto.response.StreamDiscoveryResponse;
import java.util.UUID;

public interface StreamDiscoveryService {
    /**
     * Lists live sessions, optionally filtered by their session category.
     * @param page zero-based page number
     * @param size maximum page size
     * @param categoryId optional session category identifier
     * @return the requested page of livestream cards
     */
    StreamDiscoveryResponse list(int page, int size, UUID categoryId);
}
