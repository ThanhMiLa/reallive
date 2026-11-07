package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.request.LivestreamSummariesRequest;
import com.reallive.channelservice.channel.dto.response.LivestreamSummariesResponse;

public interface LivestreamSummaryService {
    /**
     * Retrieves available channel and category summaries for livestream cards.
     * @param request identifiers to look up
     * @return summaries for identifiers that exist
     */
    LivestreamSummariesResponse getSummaries(LivestreamSummariesRequest request);
}
