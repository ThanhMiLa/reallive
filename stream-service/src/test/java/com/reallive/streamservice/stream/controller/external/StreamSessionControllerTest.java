package com.reallive.streamservice.stream.controller.external;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.common.security.CustomJwtDecoder;
import com.reallive.streamservice.common.security.JwtAccessDeniedHandler;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.SecurityConfig;
import com.reallive.streamservice.stream.dto.SessionProvisioningResult;
import com.reallive.streamservice.stream.dto.request.UpdateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.response.CreateStreamSessionResponse;
import com.reallive.streamservice.stream.dto.response.StreamPublishCredentialsResponse;
import com.reallive.streamservice.stream.service.StreamSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(controllers = StreamSessionController.class, properties = {"auth.cookie.access-token.name=access_token",
        "app.service.user=http://127.0.0.1:1/user", "app.service.channel=http://127.0.0.1:1/channel"})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class StreamSessionControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private StreamSessionService service;
    @MockitoBean private CustomJwtDecoder decoder;
    @MockitoBean private RedisAuthoritiesReader authorities;
    private final UUID owner = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    @Test
    void ownerEndpointsRequireAuthentication() throws Exception {
        mvc.perform(post("/api/streams").contentType("application/json").content(createBody()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/streams/me/current")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/streams/" + session).contentType("application/json").content("{\"title\":\"new\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/streams/" + session + "/publish-credentials")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void createAndIdempotentReplayHaveMatchingEnvelopeStatusAndNoStore() throws Exception {
        var credentials = new StreamPublishCredentialsResponse(session, "rtmp://localhost:1935/live",
                session + "?token=publish-secret", Instant.now().plusSeconds(3600));
        var response = new CreateStreamSessionResponse(null, credentials);
        when(service.create(eq(owner), any())).thenReturn(new SessionProvisioningResult(response, true),
                new SessionProvisioningResult(response, false));
        for (int status : List.of(201, 200)) {
            mvc.perform(post("/api/streams").with(jwt().jwt(token -> token.subject(owner.toString())))
                            .contentType("application/json").content(createBody()))
                    .andExpect(status().is(status)).andExpect(jsonPath("$.status").value(status))
                    .andExpect(jsonPath("$.code").value(1000)).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.result.publishCredentials.sessionId").value(session.toString()))
                    .andExpect(jsonPath("$.result.publishCredentials.streamKey").value(session + "?token=publish-secret"));
        }
    }

    @Test
    void invalidCreateAndPatchBodiesAreRejectedBeforeCallingService() throws Exception {
        for (String body : List.of("{}", "{\"requestId\":\"bad\"}",
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"title\":\"   \",\"categoryId\":\"" + UUID.randomUUID() + "\"}")) {
            mvc.perform(post("/api/streams").with(jwt()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        for (String body : List.of("{}", "{\"title\":null}", "{\"categoryId\":null}", "{\"title\":\"  \"}",
                "{\"status\":\"LIVE\"}", "{\"title\":\"new\",\"owner\":\"someone\"}",
                "{\"title\":\"" + "x".repeat(256) + "\"}")) {
            mvc.perform(patch("/api/streams/" + session).with(jwt()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void patchPreservesFieldPresenceAndUsesTheAuthenticatedOwner() throws Exception {
        mvc.perform(patch("/api/streams/" + session).with(jwt().jwt(token -> token.subject(owner.toString())))
                        .contentType("application/json").content("{\"title\":\"  Updated title  \"}"))
                .andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(UpdateStreamSessionRequest.class);
        verify(service).update(eq(session), eq(owner), request.capture());
        assertEquals("Updated title", request.getValue().getTitle());
        assertTrue(request.getValue().isTitleProvided());
        assertFalse(request.getValue().isCategoryProvided());
    }

    @Test
    void currentCanBeNullAndDoesNotContainCredentials() throws Exception {
        mvc.perform(get("/api/streams/me/current").with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("\"result\":null")))
                .andExpect(jsonPath("$.publishCredentials").doesNotExist());
        verify(service).current(owner);
    }

    @Test
    void credentialResponseIsNotCacheableAndErrorsKeepTheirHttpContract() throws Exception {
        var credentials = new StreamPublishCredentialsResponse(session, "rtmp://localhost:1935/live", "key", Instant.now());
        when(service.publishCredentials(session, owner)).thenReturn(credentials);
        mvc.perform(post("/api/streams/" + session + "/publish-credentials")
                        .with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.streamKey").value("key"));
        for (var error : List.of(ErrorCode.STREAM_SESSION_NOT_OWNED, ErrorCode.STREAM_SESSION_NOT_FOUND,
                ErrorCode.STREAM_SESSION_STATE_INVALID, ErrorCode.MEDIA_SERVER_UNAVAILABLE)) {
            doThrow(new AppException(error)).when(service).publishCredentials(session, owner);
            mvc.perform(post("/api/streams/" + session + "/publish-credentials")
                            .with(jwt().jwt(token -> token.subject(owner.toString()))))
                    .andExpect(status().is(error.getHttpStatus().value()))
                    .andExpect(jsonPath("$.status").value(error.getHttpStatus().value()))
                    .andExpect(jsonPath("$.code").value(error.getCode()));
        }
    }

    private String createBody() {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"title\":\"Stream title\",\"categoryId\":\"" + UUID.randomUUID() + "\"}";
    }
}
