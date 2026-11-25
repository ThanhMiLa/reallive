package com.reallive.streamservice.stream.controller.internal;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.security.CustomJwtDecoder;
import com.reallive.streamservice.common.security.JwtAccessDeniedHandler;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.SecurityConfig;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.impl.DonationContextServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalDonationContextController.class, properties = {
        "app.service.user=http://127.0.0.1:1/user", "app.service.channel=http://127.0.0.1:1/channel"})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        DonationContextServiceImpl.class})
class InternalDonationContextControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private StreamSessionRepository sessions;
    @MockitoBean
    private CustomJwtDecoder decoder;
    @MockitoBean
    private RedisAuthoritiesReader authorities;

    @Test
    void unauthenticatedInternalGetReturnsCompleteAuthoritativeContext() throws Exception {
        var session = StreamSessionEntity.builder().id(UUID.randomUUID()).channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID()).ownerUserId(UUID.randomUUID())
                .visibility(StreamVisibility.PUBLIC).status(StreamSessionStatus.LIVE).build();
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        mvc.perform(get("/stream/internal/api/streams/{sessionId}/donation-context", session.getId())
                        .contextPath("/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Donation context resolved"))
                .andExpect(jsonPath("$.result.sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.result.channelId").value(session.getChannelId().toString()))
                .andExpect(jsonPath("$.result.streamerId").value(session.getStreamerId().toString()))
                .andExpect(jsonPath("$.result.ownerUserId").value(session.getOwnerUserId().toString()))
                .andExpect(jsonPath("$.result.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.result.status").value("LIVE"));
        verifyNoInteractions(decoder, authorities);
    }

    @Test
    void missingSessionReturns404() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(sessions.findById(sessionId)).thenReturn(Optional.empty());
        mvc.perform(get("/stream/internal/api/streams/{sessionId}/donation-context", sessionId)
                        .contextPath("/stream"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(decoder, authorities);
    }

    @Test
    void invalidUuidReturns400WithoutLookingUpSession() throws Exception {
        mvc.perform(get("/stream/internal/api/streams/invalid/donation-context").contextPath("/stream"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sessions, decoder, authorities);
    }
}
