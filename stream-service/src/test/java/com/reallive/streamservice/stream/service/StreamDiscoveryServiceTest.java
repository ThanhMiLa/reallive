package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.client.channel.ChannelClient;
import com.reallive.streamservice.stream.client.channel.LivestreamSummaries;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.impl.StreamDiscoveryServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamDiscoveryServiceTest {
    private final StreamSessionRepository repository = mock(StreamSessionRepository.class);
    private final ChannelClient client = mock(ChannelClient.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final StreamDiscoveryServiceImpl service = new StreamDiscoveryServiceImpl(repository, client, tx);

    @Test
    void filtersBySessionCategoryAndMergesSummariesAfterTransaction() {
        UUID category = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        var session = StreamSessionEntity.builder().channelId(channel).categoryId(category).title("Live")
                .status(StreamSessionStatus.LIVE).startedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findByStatusAndCategoryId(eq(StreamSessionStatus.LIVE), eq(category), any()))
                .thenReturn(new PageImpl<>(List.of(session), PageRequest.of(0, 20), 1));
        when(client.getLivestreamSummaries(Set.of(channel), Set.of(category))).thenAnswer(invocation -> {
            verify(tx).commit(any());
            return new LivestreamSummaries(List.of(new LivestreamSummaries.ChannelSummary(channel, "name", "slug", null)), List.of());
        });
        var result = service.list(0, 20, category);
        assertEquals(1, result.totalElements());
        assertEquals("name", result.content().getFirst().channel().name());
        assertNull(result.content().getFirst().category());
        assertNull(result.content().getFirst().thumbnailUrl());
        assertEquals("2026-01-01T00:00:00Z", result.content().getFirst().startedAt().toString());
        verify(repository).findByStatusAndCategoryId(StreamSessionStatus.LIVE, category, PageRequest.of(0, 20));
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    void emptyPageSkipsChannelServiceAndUsesUnsortedQuery() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findByStatus(eq(StreamSessionStatus.LIVE), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        assertTrue(service.list(0, 20, null).content().isEmpty());
        verify(repository).findByStatus(StreamSessionStatus.LIVE, PageRequest.of(0, 20));
        verifyNoInteractions(client);
    }
}
