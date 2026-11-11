package com.reallive.streamservice.stream.controller.external;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.security.CustomJwtDecoder;
import com.reallive.streamservice.common.security.JwtAccessDeniedHandler;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.SecurityConfig;
import com.reallive.streamservice.stream.dto.response.StreamDiscoveryResponse;
import com.reallive.streamservice.stream.service.StreamDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.hamcrest.Matchers.hasEntry;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StreamDiscoveryController.class, properties = {"auth.cookie.access-token.name=access_token", "app.service.user=http://127.0.0.1:1/user", "app.service.channel=http://127.0.0.1:1/channel"})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class StreamDiscoveryControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private StreamDiscoveryService service;
    @MockitoBean private CustomJwtDecoder decoder;
    @MockitoBean private RedisAuthoritiesReader authorities;

    @Test
    void requiresAuthenticationAndValidatesParameters() throws Exception {
        mvc.perform(get("/api/streams")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/streams").with(jwt()).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/streams").with(jwt()).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/streams").with(jwt()).param("categoryId", "bad")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void returnsPageWithOptionalCategory() throws Exception {
        UUID category = UUID.randomUUID();
        when(service.list(0, 20, category)).thenReturn(new StreamDiscoveryResponse(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/streams").with(jwt()).param("categoryId", category.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content").isArray())
                .andExpect(jsonPath("$.result.totalElements").value(0));
        verify(service).list(0, 20, category);
    }

    @Test
    void returnsViewerCountForEachStreamWithoutCaching() throws Exception {
        var active = new StreamDiscoveryResponse.Item(UUID.randomUUID(), "Active", "LIVE", null, null, null,
                UUID.randomUUID(), UUID.randomUUID(), null, null, 42L);
        var empty = new StreamDiscoveryResponse.Item(UUID.randomUUID(), "Empty", "LIVE", null, null, null,
                UUID.randomUUID(), UUID.randomUUID(), null, null, 0L);
        var unavailable = new StreamDiscoveryResponse.Item(UUID.randomUUID(), "Unavailable", "LIVE", null, null, null,
                UUID.randomUUID(), UUID.randomUUID(), null, null, null);
        when(service.list(0, 20, null))
                .thenReturn(new StreamDiscoveryResponse(List.of(active, empty, unavailable), 0, 20, 3, 1));

        mvc.perform(get("/api/streams").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.content[0].currentViewerCount").value(42))
                .andExpect(jsonPath("$.result.content[1].currentViewerCount").value(0))
                .andExpect(jsonPath("$.result.content[2]").value(
                        hasEntry("currentViewerCount", null)))
                .andExpect(jsonPath("$.result.totalElements").value(3));
    }
}
