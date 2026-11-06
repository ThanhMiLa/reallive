package com.reallive.streamservice.stream.controller.internal;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.common.security.JwtAuthenticationEntryPoint;
import com.reallive.streamservice.common.security.ThumbnailCallbackSecurityConfiguration;
import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultOutcome;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultResponse;
import com.reallive.streamservice.stream.service.ThumbnailResultService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ThumbnailResultController.class,
        properties = {
                "stream.thumbnail.callback-enabled=true",
                "stream.thumbnail.callback-internal-token=callback-token-0123456789abcdefg",
                "stream.thumbnail.public-base-url=https://thumbnails.example.com",
                "stream.thumbnail.max-jpeg-bytes=5242880",
                "ACCESS_TOKEN_COOKIE_NAME=access_token",
                "STREAM_DB_PASSWORD=test-placeholder-only"
        }
)
@EnableConfigurationProperties(ThumbnailProperties.class)
@Import({ThumbnailCallbackSecurityConfiguration.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("dev")
class ThumbnailResultControllerTest {

    private static final String TOKEN = "callback-token-0123456789abcdefg";
    private static final String PATH = "/stream/internal/api/thumbnail-results";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private ThumbnailResultService resultService;

    private String body;
    private UUID jobId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        jobId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        body = """
                {
                  "jobId": "%s",
                  "sessionId": "%s",
                  "objectKey": "livestream-thumbnails/%s/%s.jpg",
                  "contentType": "image/jpeg",
                  "sizeBytes": 48210,
                  "width": 640,
                  "height": 360
                }
                """.formatted(jobId, sessionId, sessionId, jobId);
    }

    @Test
    void callbackReturnsAppliedWithoutAnyUserAuthentication() throws Exception {
        when(resultService.apply(any())).thenReturn(
                new ThumbnailResultResponse(ThumbnailResultOutcome.APPLIED));

        mvc.perform(post(PATH).contextPath("/stream")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"outcome\":\"APPLIED\"}", true));
        verify(resultService).apply(any());
    }

    @Test
    void callbackReturnsIgnoredForAStaleResult() throws Exception {
        when(resultService.apply(any())).thenReturn(
                new ThumbnailResultResponse(ThumbnailResultOutcome.IGNORED));

        mvc.perform(post(PATH).contextPath("/stream")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"outcome\":\"IGNORED\"}", true));
    }

    @Test
    void missingTokenIsRejectedWithTheStandard401Envelope() throws Exception {
        mvc.perform(post(PATH).contextPath("/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
        verify(resultService, never()).apply(any());
    }

    @Test
    void workerTokenCannotReplaceTheCallbackToken() throws Exception {
        mvc.perform(post(PATH).contextPath("/stream")
                        .header("X-Internal-Token", "worker-token-0123456789abcdefg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
        verify(resultService, never()).apply(any());
    }

    @Test
    void accountCookieCannotReplaceTheInternalToken() throws Exception {
        mvc.perform(post(PATH).contextPath("/stream")
                        .cookie(new Cookie("access_token", "a.b.c"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
        verify(resultService, never()).apply(any());
    }

    @Test
    void callbackDoesNotDependOnRedisAuthorities() {
        assertThat(context.getBeansOfType(RedisAuthoritiesReader.class)).isEmpty();
    }

    @Test
    void callbackRejectsMalformedUuidsBeforeCallingTheService() throws Exception {
        String invalid = body.replace(jobId.toString(), "not-a-uuid");

        mvc.perform(post(PATH).contextPath("/stream")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));
        verify(resultService, never()).apply(any());
    }

    @Test
    void callbackRejectsInvalidImageMetadataBeforeCallingTheService() throws Exception {
        for (String invalid : new String[]{
                body.replace("\"image/jpeg\"", "\"image/png\""),
                body.replace("\"sizeBytes\": 48210", "\"sizeBytes\": 0"),
                body.replace("\"width\": 640", "\"width\": 0"),
                body.replace("\"height\": 360", "\"height\": -1")
        }) {
            mvc.perform(post(PATH).contextPath("/stream")
                            .header("X-Internal-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1004));
        }
        verify(resultService, never()).apply(any());
    }

    @Test
    void callbackReturnsTheInvalidResultEnvelopeWhenMetadataCannotMatchTheSession() throws Exception {
        when(resultService.apply(any())).thenThrow(
                new AppException(ErrorCode.THUMBNAIL_RESULT_INVALID));

        mvc.perform(post(PATH).contextPath("/stream")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1015))
                .andExpect(jsonPath("$.message").value("Invalid thumbnail result"));
    }
}
