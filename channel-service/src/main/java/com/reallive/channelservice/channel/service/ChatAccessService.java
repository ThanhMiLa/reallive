package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.response.ChatAccessResponse;
import java.util.UUID;

public interface ChatAccessService {
    /**
     * Resolves current channel access using the latest action of each ban type.
     * @param channelId channel to access
     * @param userId user whose permissions are requested
     * @return effective viewing and chat permissions for the requested user
     * @throws com.reallive.channelservice.common.exception.AppException if the channel does not exist
     */
    ChatAccessResponse resolve(UUID channelId, UUID userId);
}
