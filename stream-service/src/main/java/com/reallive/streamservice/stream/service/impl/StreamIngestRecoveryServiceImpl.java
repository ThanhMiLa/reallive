package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.service.StreamIngestRecoveryService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamIngestRecoveryServiceImpl implements StreamIngestRecoveryService {

    ChannelStreamConfigRepository configs;
    MediaServerAdapter media;
    PlatformTransactionManager transactionManager;

    @Override
    public int restoreIngestPaths() {
        int restored = 0;
        var transaction = new TransactionTemplate(transactionManager);
        for (var channelId : configs.findChannelIdsWithIngestKeyByStatus(
                ChannelStreamConfigStatus.ACTIVE)) {
            boolean ensured = Boolean.TRUE.equals(transaction.execute(status -> {
                var config = configs.findByIdForUpdate(channelId).orElse(null);
                if (config == null
                        || config.getStatus() != ChannelStreamConfigStatus.ACTIVE
                        || config.getCurrentIngestKeyId() == null) {
                    return false;
                }
                media.ensureIngestPath(config.getCurrentIngestKeyId());
                return true;
            }));
            if (ensured) {
                restored++;
            }
        }
        return restored;
    }
}
