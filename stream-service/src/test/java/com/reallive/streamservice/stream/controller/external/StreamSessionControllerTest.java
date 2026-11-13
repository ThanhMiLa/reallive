package com.reallive.streamservice.stream.controller.external;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.common.security.CustomJwtDecoder;
import com.reallive.streamservice.common.security.JwtAccessDeniedHandler;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.SecurityConfig;
import com.reallive.streamservice.stream.dto.response.StreamDetailsResponse;
import com.reallive.streamservice.stream.dto.response.StreamMetadataResponse;
import com.reallive.streamservice.stream.dto.response.StreamOperationResponse;
import com.reallive.streamservice.stream.dto.response.StreamPlaybackAccessResponse;
import com.reallive.streamservice.stream.dto.response.StreamSetupResponse;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.service.StreamDetailsService;
import com.reallive.streamservice.stream.service.StreamPlaybackService;
import com.reallive.streamservice.stream.service.StreamSessionService;
import com.reallive.streamservice.stream.service.StreamSetupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StreamSessionController.class, properties = {
        "auth.cookie.access-token.name=access_token",
        "app.service.user=http://127.0.0.1:1/user",
        "app.service.channel=http://127.0.0.1:1/channel"
})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class StreamSessionControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private StreamSetupService setupService;
    @MockitoBean private StreamSessionService service;
    @MockitoBean private StreamPlaybackService playbackService;
    @MockitoBean private StreamDetailsService detailsService;
    @MockitoBean private CustomJwtDecoder decoder;
    @MockitoBean private RedisAuthoritiesReader authorities;

    private final UUID owner = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID operationId = UUID.randomUUID();

    @Test
    void allEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/streams/me/setup")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/streams/me/setup").contentType("application/json").content(setupBody()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/streams/me/stream-key/reset").header("Idempotency-Key", "k1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/streams/me/operations/" + operationId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/streams/me/current")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/streams/" + session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/streams/" + session + "/details")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/streams/" + session + "/playback-access")).andExpect(status().isUnauthorized());
        verifyNoInteractions(setupService, service, playbackService, detailsService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_STREAMER", "STREAM_UNRELATED"})
    void ownerEndpointsRejectMissingPermissionsBeforeCallingServices(String authority) throws Exception {
        mvc.perform(get("/api/streams/me/setup").with(ownerWithPermissions(authority)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        mvc.perform(put("/api/streams/me/setup").with(ownerWithPermissions(authority))
                        .contentType("application/json").content(setupBody()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        mvc.perform(post("/api/streams/me/stream-key/reset").with(ownerWithPermissions(authority))
                        .header("Idempotency-Key", "k1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        mvc.perform(get("/api/streams/me/operations/" + operationId).with(ownerWithPermissions(authority)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        mvc.perform(get("/api/streams/me/current").with(ownerWithPermissions(authority)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(setupService, service, playbackService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STREAM_VIEW_OWN", "STREAM_PUBLISH"})
    void getSetupRequiresBothViewOwnAndPublish(String permission) throws Exception {
        mvc.perform(get("/api/streams/me/setup").with(ownerWithPermissions(permission)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(setupService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STREAM_UPDATE", "STREAM_PUBLISH"})
    void saveSetupRequiresBothUpdateAndPublish(String permission) throws Exception {
        mvc.perform(put("/api/streams/me/setup").with(ownerWithPermissions(permission))
                        .contentType("application/json").content(setupBody()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(setupService);
    }

    @Test
    void legacySessionCreatePatchCredentialAndEndRoutesAreRemoved() throws Exception {
        var auth = ownerWithPermissions("STREAM_CREATE", "STREAM_PUBLISH", "STREAM_UPDATE", "STREAM_END", "STREAM_VIEW_OWN");
        assertTrue(mvc.perform(post("/api/streams").with(auth).contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus() >= 400);
        assertTrue(mvc.perform(patch("/api/streams/" + session).with(auth).contentType("application/json").content("{\"title\":\"x\"}"))
                .andReturn().getResponse().getStatus() >= 400);
        assertTrue(mvc.perform(post("/api/streams/" + session + "/publish-credentials").with(auth))
                .andReturn().getResponse().getStatus() >= 400);
        assertTrue(mvc.perform(post("/api/streams/" + session + "/end").with(auth))
                .andReturn().getResponse().getStatus() >= 400);
        verifyNoInteractions(setupService, service, playbackService, detailsService);
    }

    @Test
    void getAndSaveSetupAreNoStoreAndValidateBody() throws Exception {
        var activeSetup = new StreamSetupResponse(
                channelId, "My stream", categoryId, StreamVisibility.PUBLIC,
                ChannelStreamConfigStatus.ACTIVE, 1L, null,
                "rtmp://localhost:1935/ingest", "ingest-uuid?token=secret-jwt", true, null
        );
        when(setupService.getOrCreateSetup(owner)).thenReturn(activeSetup);
        when(setupService.saveSetup(eq(owner), any())).thenReturn(activeSetup);

        mvc.perform(get("/api/streams/me/setup").with(ownerWithPermissions("STREAM_VIEW_OWN", "STREAM_PUBLISH")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.channelId").value(channelId.toString()))
                .andExpect(jsonPath("$.result.streamKey").value("ingest-uuid?token=secret-jwt"))
                .andExpect(jsonPath("$.result.publishReady").value(true));

        mvc.perform(put("/api/streams/me/setup").with(ownerWithPermissions("STREAM_UPDATE", "STREAM_PUBLISH"))
                        .contentType("application/json").content(setupBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.status").value("ACTIVE"));

        for (String invalidBody : List.of("{}", "{\"title\":\"   \",\"categoryId\":\"" + categoryId + "\"}",
                "{\"title\":\"ok\"}", "{\"title\":\"" + "x".repeat(256) + "\",\"categoryId\":\"" + categoryId + "\"}")) {
            mvc.perform(put("/api/streams/me/setup").with(ownerWithPermissions("STREAM_UPDATE", "STREAM_PUBLISH"))
                            .contentType("application/json").content(invalidBody))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1004));
        }
    }

    @Test
    void resetStreamKeyReturnsAcceptedAndRequiresIdempotencyKey() throws Exception {
        var op = new StreamOperationResponse(
                operationId, MediaOperationType.RESET_KEY, MediaOperationStatus.PENDING,
                channelId, 2L, session, null
        );
        when(setupService.resetStreamKey(owner, "reset-req-1")).thenReturn(op);
        when(setupService.getOperation(owner, operationId)).thenReturn(op);

        mvc.perform(post("/api/streams/me/stream-key/reset")
                        .with(ownerWithPermissions("STREAM_PUBLISH")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));

        mvc.perform(post("/api/streams/me/stream-key/reset")
                        .with(ownerWithPermissions("STREAM_PUBLISH"))
                        .header("Idempotency-Key", "reset-req-1"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.result.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.result.operationType").value("RESET_KEY"));

        mvc.perform(get("/api/streams/me/operations/" + operationId)
                        .with(ownerWithPermissions("STREAM_VIEW_OWN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.operationId").value(operationId.toString()));
    }

    @Test
    void currentCanBeNullAndDoesNotContainCredentials() throws Exception {
        mvc.perform(get("/api/streams/me/current").with(ownerWithPermissions("STREAM_VIEW_OWN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("\"result\":null")))
                .andExpect(jsonPath("$.publishCredentials").doesNotExist());
        verify(service).current(owner);
    }

    @Test
    void viewerMetadataDetailsAndPlaybackAccessPreserveContract() throws Exception {
        var startedAt = Instant.parse("2026-10-29T01:00:00Z");
        for (var status : List.of(StreamSessionStatus.LIVE, StreamSessionStatus.RECONNECTING, StreamSessionStatus.ENDED)) {
            var metadata = new StreamMetadataResponse(session, channelId, owner, "Title", categoryId,
                    StreamVisibility.PUBLIC, status, startedAt, null, null, Instant.parse("2026-10-29T02:00:00Z"), null);
            when(service.metadata(session)).thenReturn(metadata);

            mvc.perform(get("/api/streams/" + session).with(jwt()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.result.status").value(status.name()))
                    .andExpect(jsonPath("$.result.streamKey").doesNotExist());
        }

        var details = new StreamDetailsResponse(
                session, channelId, categoryId,
                new StreamDetailsResponse.ChannelSummary(channelId, "Streamer", "https://cdn.example.com/avatar.jpg"),
                new StreamDetailsResponse.CategorySummary(categoryId, "IRL")
        );
        when(detailsService.details(session)).thenReturn(details);
        mvc.perform(get("/api/streams/" + session + "/details").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.channel.name").value("Streamer"));

        var expiresAt = Instant.parse("2026-10-29T02:05:00Z");
        when(playbackService.access(session, owner)).thenReturn(new StreamPlaybackAccessResponse(
                session, "http://localhost:8898/live/" + session + "/index.m3u8", "read.jwt.value", expiresAt));
        mvc.perform(post("/api/streams/" + session + "/playback-access")
                        .with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.playbackUrl")
                        .value("http://localhost:8898/live/" + session + "/index.m3u8"));
    }

    @Test
    void setupErrorCodesMapCorrectHttpStatus() throws Exception {
        for (var error : List.of(ErrorCode.STREAM_KEY_NOT_PROVISIONED, ErrorCode.STREAM_KEY_ROTATION_IN_PROGRESS,
                ErrorCode.STREAM_CONFIG_BLOCKED, ErrorCode.STREAMER_NOT_ELIGIBLE)) {
            doThrow(new AppException(error)).when(setupService).resetStreamKey(owner, "k-err");
            mvc.perform(post("/api/streams/me/stream-key/reset")
                            .with(ownerWithPermissions("STREAM_PUBLISH"))
                            .header("Idempotency-Key", "k-err"))
                    .andExpect(status().is(error.getHttpStatus().value()))
                    .andExpect(jsonPath("$.code").value(error.getCode()));
        }
    }

    private String setupBody() {
        return "{\"title\":\"Stream title\",\"categoryId\":\"" + categoryId + "\"}";
    }

    private JwtRequestPostProcessor ownerWithPermissions(String... permissions) {
        return jwt().jwt(token -> token.subject(owner.toString()))
                .authorities(Arrays.stream(permissions).map(SimpleGrantedAuthority::new)
                        .toArray(SimpleGrantedAuthority[]::new));
    }
}
