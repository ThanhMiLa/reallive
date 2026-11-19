package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.client.user.UserClient;
import com.reallive.streamservice.client.user.ViewerProfile;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.redis.ViewerScanPage;
import com.reallive.streamservice.stream.dto.response.StreamViewerPageResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.impl.StreamViewerListServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamViewerListServiceTest {

    private final StreamSessionRepository sessions = mock(StreamSessionRepository.class);
    private final StreamViewerPresenceService presence = mock(StreamViewerPresenceService.class);
    private final UserClient userClient = mock(UserClient.class);
    private final PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
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
    private final StreamViewerListService service = new StreamViewerListServiceImpl(
            sessions, presence, userClient, transactionManager);

    private UUID sessionId;
    private UUID ownerId;
    private StreamSessionEntity session;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        session = new StreamSessionEntity();
        session.setId(sessionId);
        session.setOwnerUserId(ownerId);
        session.setStatus(StreamSessionStatus.LIVE);
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
    }

    @Test
    void validatesAuthenticationSessionAndPaginationArguments() {
        assertThatThrownBy(() -> service.list(sessionId, null, "0", 50))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));

        for (int badSize : List.of(0, -1, 51)) {
            assertThatThrownBy(() -> service.list(sessionId, ownerId, "0", badSize))
                    .isInstanceOfSatisfying(AppException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STREAM_REQUEST_INVALID));
        }

        for (String badCursor : List.of("", " ", "-1", "00", "01", "abc", "18446744073709551616")) {
            assertThatThrownBy(() -> service.list(sessionId, ownerId, badCursor, 50))
                    .isInstanceOfSatisfying(AppException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STREAM_REQUEST_INVALID));
        }

        assertThatThrownBy(() -> service.list(null, ownerId, "0", 50))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STREAM_REQUEST_INVALID));

        verifyNoInteractions(sessions, presence, userClient);
    }

    @Test
    void rejectsMissingSessionAndNonOwnerBeforeScanningPresence() {
        UUID missing = UUID.randomUUID();
        when(sessions.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(missing, ownerId, "0", 50))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STREAM_SESSION_NOT_FOUND));

        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.list(sessionId, stranger, "0", 50))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STREAM_SESSION_NOT_OWNED));

        verifyNoInteractions(presence, userClient);
    }

    @Test
    void inactiveOrEmptyScanSkipsUserProfileLookup() {
        session.setStatus(StreamSessionStatus.ENDED);
        when(presence.scan(sessionId, StreamSessionStatus.ENDED, "0", 50)).thenReturn(ViewerScanPage.closed());
        assertThat(service.list(sessionId, ownerId, "0", 50))
                .isEqualTo(new StreamViewerPageResponse(List.of(), null, false));

        session.setStatus(StreamSessionStatus.LIVE);
        when(presence.scan(sessionId, StreamSessionStatus.LIVE, "16", 50))
                .thenReturn(new ViewerScanPage(ViewerScanPage.Status.ACTIVE, "32", List.of()));
        assertThat(service.list(sessionId, ownerId, "16", 50))
                .isEqualTo(new StreamViewerPageResponse(List.of(), "32", true));

        verifyNoInteractions(userClient);
    }

    @Test
    void enrichesActiveViewersPreservingOrderAndCursorState() {
        UUID first = UUID.randomUUID();
        UUID missingProfile = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(presence.scan(sessionId, StreamSessionStatus.LIVE, "0", 20))
                .thenReturn(new ViewerScanPage(ViewerScanPage.Status.ACTIVE, "48", List.of(first, missingProfile, second)));
        when(userClient.getViewerProfiles(List.of(first, missingProfile, second))).thenReturn(Map.of(
                first, new ViewerProfile(first, "Alice", "https://cdn.example.com/a.jpg"),
                second, new ViewerProfile(second, "Bob", null)));

        StreamViewerPageResponse page = service.list(sessionId, ownerId, "0", 20);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("48");
        assertThat(page.items()).containsExactly(
                new StreamViewerPageResponse.ViewerItem(first, "Alice", "https://cdn.example.com/a.jpg"),
                new StreamViewerPageResponse.ViewerItem(second, "Bob", null));

        when(presence.scan(sessionId, StreamSessionStatus.RECONNECTING, "48", 20))
                .thenReturn(new ViewerScanPage(ViewerScanPage.Status.ACTIVE, "0", List.of(second)));
        when(userClient.getViewerProfiles(List.of(second))).thenReturn(Map.of(
                second, new ViewerProfile(second, "Bob", null)));
        session.setStatus(StreamSessionStatus.RECONNECTING);
        StreamViewerPageResponse lastPage = service.list(sessionId, ownerId, "48", 20);
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(lastPage.nextCursor()).isNull();
        assertThat(lastPage.items()).containsExactly(
                new StreamViewerPageResponse.ViewerItem(second, "Bob", null));
        verify(userClient).getViewerProfiles(List.of(second));
    }
}
