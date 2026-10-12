package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.common.exception.AppException;

public interface ChannelService {

    /**
     * Creates an active channel with a deterministic slug for the supplied streamer.
     *
     * <p>Repeated and concurrent calls succeed without changing the existing channel's
     * profile, timestamps, banned status or soft-delete state. Streamer existence is not
     * verified with User Service. Creation commits independently of any caller transaction.</p>
     *
     * @param request the streamer UUID, channel name and optional HTTP/HTTPS avatar URL
     * @return {@code true} when the channel was created or already exists for the streamer
     * @throws AppException if the request is invalid or channel storage is unavailable
     */
    boolean createChannel(CreateChannelRequest request);
}
