package com.reallive.paymentservice.common.security;

import com.reallive.paymentservice.common.adapter.redis.RedisAuthoritiesReader;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalEndpointSecurityTest.TestController.class,
        properties = {"auth.cookie.access-token.name=access_token",
                "app.service.user=http://127.0.0.1:1/user",
                "app.service.channel=http://127.0.0.1:1/channel",
                "app.service.payment=http://127.0.0.1:1/payment"})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        InternalEndpointSecurityTest.TestController.class})
class InternalEndpointSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CustomJwtDecoder decoder;

    @MockitoBean
    RedisAuthoritiesReader authorities;

    @ParameterizedTest
    @CsvSource({
            "POST, /internal/api/wallets/create/id"
    })
    void internalCallsBypassAllTokenVerification(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path))
                .andExpect(status().isOk()).andExpect(content().string("internal"));
        mvc.perform(request(HttpMethod.valueOf(method), path)
                        .cookie(new Cookie("access_token", "malformed"))
                        .header("Authorization", "Bearer malformed")
                        .header("X-Internal-Token", "ignored"))
                .andExpect(status().isOk()).andExpect(content().string("internal"));
        HttpMethod unsupportedMethod = method.equals("GET") ? HttpMethod.POST : HttpMethod.GET;
        mvc.perform(request(unsupportedMethod, path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(decoder, authorities);
    }

    @Test
    void clientFacingAndUnregisteredInternalPathsStayProtected() throws Exception {
        mvc.perform(get("/api/private")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/api/unknown")).andExpect(status().isUnauthorized());
        verifyNoInteractions(decoder, authorities);
    }

    @RestController
    static class TestController {
        @RequestMapping(path = "/internal/api/wallets/create/id", method = RequestMethod.POST)
        String internal0() {
            return "internal";
        }
    }
}
