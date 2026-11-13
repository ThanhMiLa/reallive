package com.reallive.streamservice.stream.controller.media;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.configuration.MediaConfiguration;
import com.reallive.streamservice.common.configuration.StreamClockConfig;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.common.security.CustomJwtDecoder;
import com.reallive.streamservice.common.security.JwtAccessDeniedHandler;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.MediaCallbackSecurityConfiguration;
import com.reallive.streamservice.common.security.SecurityConfig;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.response.MediaEventResponse;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.mapper.MediaEventMapper;
import com.reallive.streamservice.stream.service.StreamMediaLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MediaEventController.class, properties = {
        "auth.cookie.access-token.name=access_token"
})
@Import({SecurityConfig.class, MediaCallbackSecurityConfiguration.class, MediaConfiguration.class,
        CustomJwtDecoder.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        StreamClockConfig.class, MediaEventMapper.class})
class MediaEventControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private StreamMediaLifecycleService lifecycleService;
    @MockitoBean private RedisAuthoritiesReader authorities;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MediaTestSupport.register(registry);
    }

    @Test
    void missingOrWrongInternalTokenIsRejectedUnauthorized() throws Exception {
        byte[] body = mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "ONLINE",
                "path", "ingest/" + UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/internal/api/media-events").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/api/media-events")
                        .header("X-Internal-Token", "invalid-token")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(lifecycleService, authorities);
    }

    @Test
    void onlineEventReturnsAcceptedWithNullableSessionBeforeWorkerRuns() throws Exception {
        UUID ingestKeyId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        when(lifecycleService.onOnline(any()))
                .thenReturn(new MediaEventResponse(null, eventId, MediaEventOutcome.ACCEPTED, null));

        byte[] body = mapper.writeValueAsBytes(Map.of(
                "eventId", eventId,
                "eventType", "ONLINE",
                "path", "ingest/" + ingestKeyId,
                "sourceId", sourceId,
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/internal/api/media-events")
                        .header("X-Internal-Token", MediaTestSupport.CALLBACK_INTERNAL_TOKEN)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.result.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.result.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.result.streamId").isEmpty())
                .andExpect(jsonPath("$.result.status").isEmpty());

        verify(lifecycleService).onOnline(any());
        verifyNoInteractions(authorities);
    }

    @Test
    void offlineEventReturnsAcceptedWithCurrentSessionSnapshot() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID ingestKeyId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        when(lifecycleService.onOffline(any()))
                .thenReturn(new MediaEventResponse(streamId, eventId, MediaEventOutcome.ACCEPTED, StreamSessionStatus.LIVE));

        byte[] body = mapper.writeValueAsBytes(Map.of(
                "eventId", eventId,
                "eventType", "OFFLINE",
                "path", "ingest/" + ingestKeyId,
                "sourceId", sourceId,
                "sourceType", "rtmpsConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/internal/api/media-events")
                        .header("X-Internal-Token", MediaTestSupport.CALLBACK_INTERNAL_TOKEN)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.result.streamId").value(streamId.toString()))
                .andExpect(jsonPath("$.result.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.result.status").value("LIVE"));

        verify(lifecycleService).onOffline(any());
    }

    @Test
    void invalidRequestBodyReturnsBadRequest() throws Exception {
        byte[] invalidPath = mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "ONLINE",
                "path", "live/" + UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/internal/api/media-events")
                        .header("X-Internal-Token", MediaTestSupport.CALLBACK_INTERNAL_TOKEN)
                        .contentType("application/json")
                        .content(invalidPath))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        byte[] invalidType = mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "SOMETHING",
                "path", "ingest/" + UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/internal/api/media-events")
                        .header("X-Internal-Token", MediaTestSupport.CALLBACK_INTERNAL_TOKEN)
                        .contentType("application/json")
                        .content(invalidType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(lifecycleService);
    }

    @Test
    void ingestKeyNotFoundReturnsNotFound() throws Exception {
        when(lifecycleService.onOnline(any())).thenThrow(new AppException(ErrorCode.STREAM_INGEST_KEY_NOT_FOUND));

        byte[] body = mapper.writeValueAsBytes(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "ONLINE",
                "path", "ingest/" + UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "sourceType", "rtmpConn",
                "occurredAt", Instant.now()
        ));

        mvc.perform(post("/internal/api/media-events")
                        .header("X-Internal-Token", MediaTestSupport.CALLBACK_INTERNAL_TOKEN)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(1022));
    }
}
