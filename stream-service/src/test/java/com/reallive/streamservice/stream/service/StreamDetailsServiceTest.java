package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.client.channel.ChannelClient;
import com.reallive.streamservice.client.channel.LivestreamSummaries;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.impl.StreamDetailsServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamDetailsServiceTest {

    private final StreamSessionRepository repository = mock(StreamSessionRepository.class);
    private final ChannelClient client = mock(ChannelClient.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final StreamDetailsServiceImpl service = new StreamDetailsServiceImpl(repository, client, tx);

    @ParameterizedTest
    @EnumSource(StreamSessionStatus.class)
    void returnsChannelAndCategorySummariesAfterReadOnlyTransactionForEveryPublicStatus(StreamSessionStatus status) {
        reset(repository, client, tx);
        UUID sessionId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var session = StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(channelId)
                .categoryId(categoryId)
                .visibility(StreamVisibility.PUBLIC)
                .status(status)
                .build();

        when(tx.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition def = invocation.getArgument(0);
            assertTrue(def.isReadOnly());
            return new SimpleTransactionStatus();
        });
        when(repository.findById(sessionId)).thenReturn(Optional.of(session));
        when(client.getLivestreamSummaries(Set.of(channelId), Set.of(categoryId))).thenAnswer(invocation -> {
            verify(tx).commit(any());
            return new LivestreamSummaries(
                    List.of(new LivestreamSummaries.ChannelSummary(channelId, "Queenofdarknesstv", "queen", "https://cdn.example.com/avatar.jpg")),
                    List.of(new LivestreamSummaries.CategorySummary(categoryId, "IRL", "irl"))
            );
        });

        var result = service.details(sessionId);

        assertEquals(sessionId, result.id());
        assertEquals(channelId, result.channelId());
        assertEquals(categoryId, result.categoryId());
        assertNotNull(result.channel());
        assertEquals(channelId, result.channel().id());
        assertEquals("Queenofdarknesstv", result.channel().name());
        assertEquals("https://cdn.example.com/avatar.jpg", result.channel().avatarUrl());
        assertNotNull(result.category());
        assertEquals(categoryId, result.category().id());
        assertEquals("IRL", result.category().name());
    }

    @Test
    void returnsNullSummariesWhenChannelServiceBatchOmitsMetadata() {
        UUID sessionId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var session = StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(channelId)
                .categoryId(categoryId)
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .build();

        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findById(sessionId)).thenReturn(Optional.of(session));
        when(client.getLivestreamSummaries(Set.of(channelId), Set.of(categoryId)))
                .thenReturn(new LivestreamSummaries(List.of(), List.of()));

        var result = service.details(sessionId);

        assertEquals(sessionId, result.id());
        assertEquals(channelId, result.channelId());
        assertEquals(categoryId, result.categoryId());
        assertNull(result.channel());
        assertNull(result.category());
    }

    @Test
    void rejectsMissingAndNonPublicSessionsWithoutCallingChannelService() {
        UUID missingId = UUID.randomUUID();
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        var notFound = assertThrows(AppException.class, () -> service.details(missingId));
        assertEquals(ErrorCode.STREAM_SESSION_NOT_FOUND, notFound.getErrorCode());

        UUID privateId = UUID.randomUUID();
        var privateSession = StreamSessionEntity.builder()
                .id(privateId)
                .channelId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .visibility(StreamVisibility.PRIVATE)
                .status(StreamSessionStatus.LIVE)
                .build();
        when(repository.findById(privateId)).thenReturn(Optional.of(privateSession));

        var denied = assertThrows(AppException.class, () -> service.details(privateId));
        assertEquals(ErrorCode.ACCESS_DENIED, denied.getErrorCode());
        verifyNoInteractions(client);
    }

    @Test
    void propagatesServiceUnavailableWhenChannelClientFails() {
        UUID sessionId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var session = StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(channelId)
                .categoryId(categoryId)
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .build();

        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findById(sessionId)).thenReturn(Optional.of(session));
        when(client.getLivestreamSummaries(Set.of(channelId), Set.of(categoryId)))
                .thenThrow(new AppException(ErrorCode.STREAM_DATA_UNAVAILABLE));

        var error = assertThrows(AppException.class, () -> service.details(sessionId));
        assertEquals(ErrorCode.STREAM_DATA_UNAVAILABLE, error.getErrorCode());
    }
}
