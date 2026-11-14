package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.mapper.StreamSessionMapper;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.impl.StreamSessionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamMetadataViewerCountTest {
    @Test void metadataIncludesPresenceWithoutMediaCallsOrMutatingPresence() {
        var id = UUID.randomUUID();
        var session = new StreamSessionEntity();
        session.setId(id); session.setStatus(StreamSessionStatus.LIVE); session.setVisibility(StreamVisibility.PUBLIC);
        var repository = mock(StreamSessionRepository.class);
        when(repository.findById(id)).thenReturn(Optional.of(session));
        var presence = mock(StreamViewerPresenceService.class);
        when(presence.count(id, StreamSessionStatus.LIVE)).thenReturn(17L);
        var transactions = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        var service = new StreamSessionServiceImpl(repository, null, null, new StreamSessionMapper(), transactions, Clock.systemUTC(), presence);
        assertEquals(17L, service.metadata(id).currentViewerCount());
        verify(presence).count(id, StreamSessionStatus.LIVE);
        verifyNoMoreInteractions(presence);
    }
}
