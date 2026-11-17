package com.reallive.streamservice.stream.controller.media;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.configuration.MediaConfiguration;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.common.security.CustomJwtDecoder;
import com.reallive.streamservice.common.security.JwtAccessDeniedHandler;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.SecurityConfig;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.service.StreamIngestRecoveryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MediaRecoveryController.class,
        properties = "auth.cookie.access-token.name=access_token")
@Import({SecurityConfig.class, MediaConfiguration.class, CustomJwtDecoder.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class MediaRecoveryControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private StreamIngestRecoveryService recovery;
    @MockitoBean private RedisAuthoritiesReader authorities;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MediaTestSupport.register(registry);
    }

    @Test
    void mediaStartupRestoresPathsWithoutAccountCredentials() throws Exception {
        when(recovery.restoreIngestPaths()).thenReturn(2);
        mvc.perform(post("/internal/api/media/restore-ingest-paths")
                        .header("Authorization", "Bearer ignored"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.restoredChannels").value(2));
        verify(recovery).restoreIngestPaths();
        verifyNoInteractions(authorities);
    }

    @Test
    void unavailableMediaReturnsFailureSoStartupCanRetry() throws Exception {
        when(recovery.restoreIngestPaths())
                .thenThrow(new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE));
        mvc.perform(post("/internal/api/media/restore-ingest-paths"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void wrongMethodsChildrenAndPublicAliasesRemainProtected() throws Exception {
        mvc.perform(get("/internal/api/media/restore-ingest-paths"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/api/media/restore-ingest-paths"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/api/media/restore-ingest-paths/child"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/media/restore-ingest-paths"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(recovery, authorities);
    }
}
