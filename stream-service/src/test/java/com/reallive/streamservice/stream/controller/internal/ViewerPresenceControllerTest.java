package com.reallive.streamservice.stream.controller.internal;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.common.security.CustomJwtDecoder;
import com.reallive.streamservice.common.security.JwtAccessDeniedHandler;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.SecurityConfig;
import com.reallive.streamservice.stream.adapter.redis.StreamViewerPresenceStore;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.impl.StreamViewerPresenceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = InternalViewerPresenceController.class,
        properties = {
                "stream.ccu.enabled=true",
                "auth.cookie.access-token.name=access_token",
                "ACCESS_TOKEN_COOKIE_NAME=access_token",
                "STREAM_DB_PASSWORD=test-placeholder-only"
        }
)
@Import({SecurityConfig.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        StreamViewerPresenceServiceImpl.class})
@ActiveProfiles("dev")
class ViewerPresenceControllerTest {

    private static final String PATH = "/stream/internal/api/viewer-events";
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StreamViewerPresenceStore store;

    @MockitoBean
    private StreamSessionRepository sessions;

    @MockitoBean
    private Clock streamClock;

    @MockitoBean
    private CustomJwtDecoder decoder;

    @MockitoBean
    private RedisAuthoritiesReader authorities;

    private UUID sessionId;
    private UUID readerId;
    private UUID userId;
    private UUID ownerId;
    private StreamSessionEntity session;
    private String body;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID();
        readerId = UUID.randomUUID();
        userId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        session = new StreamSessionEntity();
        session.setId(sessionId);
        session.setOwnerUserId(ownerId);
        session.setStatus(StreamSessionStatus.LIVE);
        when(streamClock.instant()).thenReturn(NOW);
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        body = """
                {
                  "eventId": "%s",
                  "eventType": "READ",
                  "sessionId": "%s",
                  "readerId": "%s",
                  "readerType": "hlsSession",
                  "userId": "%s",
                  "occurredAt": "%s"
                }
                """.formatted(UUID.randomUUID(), sessionId, readerId, userId, NOW);
    }

    @Test
    void readCallbackDoesNotRequireServiceOrAccountAuthentication() throws Exception {
        mvc.perform(post(PATH).contextPath("/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(store).join(sessionId, userId, readerId);
        verifyNoInteractions(decoder, authorities);
    }

    @Test
    void unreadCallbackDoesNotNeedAUserOrAnyControlApiLookup() throws Exception {
        String unread = body.replace("\"READ\"", "\"UNREAD\"")
                .replace("\"userId\": \"" + userId + "\"", "\"userId\": null");

        mvc.perform(post(PATH).contextPath("/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(unread))
                .andExpect(status().isOk());

        verify(store).leave(sessionId, readerId);
        verify(store, never()).join(any(), any(), any());
        verifyNoInteractions(decoder, authorities);
    }

    @Test
    void legacyInternalTokenIsIgnored() throws Exception {
        mvc.perform(post(PATH).contextPath("/stream")
                        .header("X-Internal-Token", "ignored")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(store).join(sessionId, userId, readerId);
        verifyNoInteractions(decoder, authorities);
    }

    @Test
    void authorizationHeaderDoesNotTriggerAccountAuthentication() throws Exception {
        mvc.perform(post(PATH).contextPath("/stream")
                        .header("Authorization", "Bearer malformed")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(store).join(sessionId, userId, readerId);
        verifyNoInteractions(decoder, authorities);
    }

    @Test
    void invalidIdsTypesAndRequiredFieldsReturn400BeforeProcessingPresence() throws Exception {
        for (String invalid : List.of(
                "{}",
                body.replace(sessionId.toString(), "not-a-uuid"),
                body.replace(readerId.toString(), "not-a-uuid"),
                body.replace(userId.toString(), "not-a-uuid"),
                body.replace("\"READ\"", "\"JOINED\""),
                body.replace("\"hlsSession\"", "\"rtmpConn\""),
                body.replace("\"hlsSession\"", "null"),
                body.replace("\"occurredAt\": \"" + NOW + "\"", "\"occurredAt\": null"))) {
            mvc.perform(post(PATH).contextPath("/stream")
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1004));
        }

        verifyNoInteractions(store, sessions);
    }

    @Test
    void readRequiresAUserEvenThoughUnreadDoesNot() throws Exception {
        for (String invalid : List.of(
                body.replace("\"userId\": \"" + userId + "\"", "\"userId\": null"),
                body.replace("\"userId\": \"" + userId + "\",", ""))) {
            mvc.perform(post(PATH).contextPath("/stream")
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1004));
        }

        verifyNoInteractions(store, sessions);
    }

    @Test
    void redisUnavailableReturnsRetryable503InsteadOfAcknowledgingTheCallback() throws Exception {
        doThrow(new AppException(ErrorCode.VIEWER_PRESENCE_UNAVAILABLE))
                .when(store).join(sessionId, userId, readerId);

        mvc.perform(post(PATH).contextPath("/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(1016));
    }

    @Test
    void callbackCannotBeReachedThroughThePublicApiNamespaceOrChildPaths() throws Exception {
        for (String path : List.of("/stream/api/viewer-events", PATH + "/other")) {
            mvc.perform(post(path).contextPath("/stream")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        verifyNoInteractions(store, sessions);
    }

    @Test
    void streamOwnerAndEndedReadersAreAcknowledgedWithoutAddingAViewer() throws Exception {
        mvc.perform(post(PATH).contextPath("/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(userId.toString(), ownerId.toString())))
                .andExpect(status().isOk());
        session.setStatus(StreamSessionStatus.ENDED);
        mvc.perform(post(PATH).contextPath("/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verifyNoInteractions(store);
    }

    @Test
    void readyStreamReturns409SoTheReadHookCanRetry() throws Exception {
        session.setStatus(StreamSessionStatus.READY);

        mvc.perform(post(PATH).contextPath("/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1009));

        verifyNoInteractions(store);
    }
}
