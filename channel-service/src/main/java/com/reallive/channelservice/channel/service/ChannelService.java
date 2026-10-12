package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.common.exception.AppException;

public interface ChannelService {

    /**
     * Creates an active channel with a deterministic slug for the supplied streamer.
     *
     * <p>An existing channel for the streamer is rejected without changing its data.
     * Concurrent inserts are protected by database uniqueness constraints. Streamer existence
     * is not verified with User Service. Creation joins the current local transaction when present.</p>
     *
     * @param request the streamer UUID, channel name and optional HTTP/HTTPS avatar URL
     * @return {@code true} when the saved channel has an ID, otherwise {@code false}
     * @throws AppException if a channel already exists or insertion violates a database constraint
     */
    boolean createChannel(CreateChannelRequest request);
}
