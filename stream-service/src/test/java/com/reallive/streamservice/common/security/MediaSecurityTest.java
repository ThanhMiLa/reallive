package com.reallive.streamservice.common.security;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.configuration.MediaConfiguration;
import com.reallive.streamservice.common.configuration.StreamClockConfig;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.controller.media.MediaJwksController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MediaJwksController.class, properties = "auth.cookie.access-token.name=access_token")
@Import({SecurityConfig.class, CustomJwtDecoder.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        MediaConfiguration.class, StreamClockConfig.class, MediaJwtIssuer.class})
class MediaSecurityTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MediaJwtIssuer issuer;
    @MockitoBean private RedisAuthoritiesReader authorities;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MediaTestSupport.register(registry);
    }

    @Test
    void anonymousJwksIsRawPublicJsonWithoutAnyRedisLookup() throws Exception {
        mvc.perform(get("/api/media/jwks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys", hasSize(1))).andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].n").exists()).andExpect(jsonPath("$.keys[0].e").exists())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist()).andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.result").doesNotExist());
        verifyNoInteractions(authorities);
    }

    @Test
    void wrongMethodsChildrenAndBusinessPathsRemainProtected() throws Exception {
        mvc.perform(post("/api/media/jwks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/media/jwks/other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/media/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/media/events/other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/streams")).andExpect(status().isUnauthorized());
        mvc.perform(get("/error")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/media/jwks").cookie(new Cookie("access_token", "broken"))).andExpect(status().isUnauthorized());
    }

    @Test
    void validSuppliedCookieStillRequiresAvailableAuthorities() throws Exception {
        var media = issuer.issueRead(UUID.randomUUID(), UUID.randomUUID());
        when(authorities.read(any())).thenThrow(new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE));
        // Downstream decoders trust Gateway signature validation; this test exercises the Redis boundary only.
        mvc.perform(get("/api/media/jwks").cookie(new Cookie("access_token", media.value())))
                .andExpect(status().isServiceUnavailable());
        verify(authorities).read(any());
    }

    @Test
    void directMediaCallbackReachesEndpointWithoutAccountCookie() throws Exception {
        byte[] body = mapper.writeValueAsBytes(Map.of("eventId", UUID.randomUUID(), "eventType", "ONLINE",
                "path", "live/" + UUID.randomUUID(), "sourceId", UUID.randomUUID(), "sourceType", "rtmpConn",
                "occurredAt", Instant.now()));
        // Direct media callback is permitted without account cookie; in phase 2 missing controller reaches 404
        mvc.perform(post("/api/media/events").contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        verifyNoInteractions(authorities);
    }
}
