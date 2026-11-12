package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.stream.dto.response.ChatContextResponse;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.ChatContextService;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatContextServiceImpl implements ChatContextService {
    StreamSessionRepository sessions;

    @Override
    @Transactional(readOnly = true)
    public ChatContextResponse context(UUID sessionId) {
        var session = sessions.findById(sessionId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));
        return new ChatContextResponse(session.getId(), session.getChannelId(),
                session.getVisibility().name(), session.getStatus().name());
    }
}
