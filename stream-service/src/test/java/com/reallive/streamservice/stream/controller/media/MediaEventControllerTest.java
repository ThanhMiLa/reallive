package com.reallive.streamservice.stream.controller.media;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.configuration.StreamClockConfig;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.common.security.CustomJwtDecoder;
import com.reallive.streamservice.common.security.JwtAccessDeniedHandler;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.SecurityConfig;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.SessionEventResult;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.mapper.MediaEventMapper;
import com.reallive.streamservice.stream.service.StreamMediaLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MediaEventController.class, properties = {
        "auth.cookie.access-token.name=access_token",
        "app.service.user=http://127.0.0.1:1/user",
        "app.service.channel=http://127.0.0.1:1/channel"
})
@Import({SecurityConfig.class, CustomJwtDecoder.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        StreamClockConfig.class, MediaEventMapper.class})
class MediaEventControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private StreamMediaLifecycleService lifecycleService;
    @MockitoBean private RedisAuthoritiesReader authorities;

    @Test
    void onlineEventProcessesSuccessfullyWithoutCookie() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        var session = StreamSessionEntity.builder().id(streamId).status(StreamSessionStatus.LIVE).build();
        when(lifecycleService.onOnline(any())).thenReturn(new SessionEventResult(session, MediaEventOutcome.APPLIED));

        byte[] body = mapper.writeValueAsBytes(Map.of(
                "eventId", eventId,
                "eventType", "ONLINE",
                "path", "live/" + streamId,
                "sourceId", sourceId,
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/api/media/events").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.result.streamId").value(streamId.toString()))
                .andExpect(jsonPath("$.result.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.result.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.result.status").value("LIVE"));

        verify(lifecycleService).onOnline(any());
        verifyNoInteractions(authorities);
    }

    @Test
    void offlineEventProcessesSuccessfullyWithoutCookie() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        var session = StreamSessionEntity.builder().id(streamId).status(StreamSessionStatus.RECONNECTING).build();
        when(lifecycleService.onOffline(any())).thenReturn(new SessionEventResult(session, MediaEventOutcome.APPLIED));

        byte[] body = mapper.writeValueAsBytes(Map.of(
                "eventId", eventId,
                "eventType", "OFFLINE",
                "path", "live/" + streamId,
                "sourceId", sourceId,
                "sourceType", "rtmpsConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/api/media/events").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.result.streamId").value(streamId.toString()))
                .andExpect(jsonPath("$.result.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.result.status").value("RECONNECTING"));

        verify(lifecycleService).onOffline(any());
    }

    @Test
    void invalidRequestBodyReturnsBadRequest() throws Exception {
        byte[] invalidPath = mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "ONLINE",
                "path", "invalid/" + UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/api/media/events").contentType("application/json").content(invalidPath))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        byte[] invalidType = mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "SOMETHING",
                "path", "live/" + UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/api/media/events").contentType("application/json").content(invalidType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(lifecycleService);
    }

    @Test
    void sessionNotFoundReturnsNotFound() throws Exception {
        when(lifecycleService.onOnline(any())).thenThrow(new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));

        byte[] body = mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "ONLINE",
                "path", "live/" + UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/api/media/events").contentType("application/json").content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void mediaServerUnavailableReturnsServiceUnavailable() throws Exception {
        when(lifecycleService.onOnline(any())).thenThrow(new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE));

        byte[] body = mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "ONLINE",
                "path", "live/" + UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/api/media/events").contentType("application/json").content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }
}
