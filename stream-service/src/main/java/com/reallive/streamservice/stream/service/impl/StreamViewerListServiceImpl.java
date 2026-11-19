package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.client.user.UserClient;
import com.reallive.streamservice.client.user.ViewerProfile;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.redis.ViewerScanPage;
import com.reallive.streamservice.stream.dto.response.StreamViewerPageResponse;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamViewerListService;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(propagation = Propagation.NEVER)
public class StreamViewerListServiceImpl implements StreamViewerListService {

    static final int MAX_PAGE_SIZE = 50;
    static final Pattern CURSOR_PATTERN = Pattern.compile("^(0|[1-9][0-9]*)$");

    StreamSessionRepository sessions;
    StreamViewerPresenceService viewerPresence;
    UserClient userClient;
    PlatformTransactionManager transactionManager;

    @Override
    public StreamViewerPageResponse list(UUID sessionId, UUID requesterUserId, String cursor, int size) {
        if (requesterUserId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (sessionId == null || size < 1 || size > MAX_PAGE_SIZE) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        String validCursor = validateCursor(cursor);

        TransactionTemplate readTx = new TransactionTemplate(transactionManager);
        readTx.setReadOnly(true);
        StreamSessionStatus sessionStatus = readTx.execute(status -> {
            var session = sessions.findById(sessionId)
                    .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));
            if (!requesterUserId.equals(session.getOwnerUserId())) {
                throw new AppException(ErrorCode.STREAM_SESSION_NOT_OWNED);
            }
            return session.getStatus();
        });

        ViewerScanPage scanPage = viewerPresence.scan(sessionId, sessionStatus, validCursor, size);
        boolean hasMore = scanPage.status() == ViewerScanPage.Status.ACTIVE && !"0".equals(scanPage.nextCursor());
        String nextCursor = hasMore ? scanPage.nextCursor() : null;
        if (scanPage.viewerIds().isEmpty()) {
            return new StreamViewerPageResponse(List.of(), nextCursor, hasMore);
        }

        Map<UUID, ViewerProfile> profiles = userClient.getViewerProfiles(scanPage.viewerIds());
        List<StreamViewerPageResponse.ViewerItem> items = scanPage.viewerIds().stream()
                .map(profiles::get)
                .filter(Objects::nonNull)
                .map(profile -> new StreamViewerPageResponse.ViewerItem(
                        profile.userId(),
                        profile.displayName(),
                        profile.avatarUrl()))
                .toList();
        return new StreamViewerPageResponse(items, nextCursor, hasMore);
    }

    private String validateCursor(String cursor) {
        if (cursor == null || !CURSOR_PATTERN.matcher(cursor).matches()) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        try {
            Long.parseUnsignedLong(cursor);
        } catch (NumberFormatException exception) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        return cursor;
    }
}
