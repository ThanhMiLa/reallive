package com.reallive.userservice.auth.controller;

import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.AuthService;
import com.reallive.userservice.auth.service.impl.AuthServiceImpl;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.exception.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthRefreshTest {

    private final List<AnnotationConfigWebApplicationContext> contexts = new ArrayList<>();
    private final UserRepository userRepository = mock(UserRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final AuthServiceImpl authService = new AuthServiceImpl(userRepository, mock(UserMapper.class),
            mock(PasswordEncoder.class), mock(RoleRepository.class), redisTemplate);

    @AfterEach
    void closeContexts() {
        contexts.forEach(AnnotationConfigWebApplicationContext::close);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void refreshRejectsMissingTokenBeforeAccessingRedisOrDatabase(String token) {
        AppException exception = assertThrows(AppException.class, () -> authService.refresh(token));

        assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        verifyNoInteractions(userRepository, redisTemplate);
    }

    @Test
    void refreshWithoutCookieReturnsUnauthorized() throws Exception {
        mockMvc(new AuthController(authService), "refresh_token")
                .perform(post("/user/api/auth/refresh").contextPath("/user"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));

        verifyNoInteractions(userRepository, redisTemplate);
    }

    @Test
    void refreshReadsConfiguredCookieName() throws Exception {
        AuthService service = mock(AuthService.class);
        when(service.refresh("existing-refresh-token")).thenReturn(tokens());

        mockMvc(configuredController(service, "/user/api/auth"), "custom_refresh")
                .perform(post("/user/api/auth/refresh").contextPath("/user")
                        .cookie(new Cookie("custom_refresh", "existing-refresh-token")))
                .andExpect(status().isOk());

        verify(service).refresh("existing-refresh-token");
    }

    @Test
    void loginCookiePathAllowsSendingRefreshCookieToRefreshEndpoint() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-dev.yaml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new PropertiesPropertySource("application", yaml.getObject()));
        String contextPath = environment.getRequiredProperty("server.servlet.context-path");
        String refreshPath = environment.getRequiredProperty("auth.cookie.refresh-token.path");

        AuthService service = mock(AuthService.class);
        when(service.login(any())).thenReturn(tokens());
        List<String> cookies = mockMvc(configuredController(service, refreshPath), "custom_refresh")
                .perform(post(contextPath + "/api/auth/login").contextPath(contextPath)
                        .contentType("application/json")
                        .content("{\"username\":\"tester\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        cookieManager.put(URI.create("http://localhost:8080" + contextPath + "/api/auth/login"),
                Map.of("Set-Cookie", cookies));
        Map<String, List<String>> requestHeaders = cookieManager.get(
                URI.create("http://localhost:8080" + contextPath + "/api/auth/refresh"), Map.of());

        assertTrue(requestHeaders.get("Cookie").stream()
                .anyMatch(cookie -> cookie.contains("custom_refresh=refresh-token")));
    }

    private MockMvc mockMvc(AuthController controller, String refreshCookieName) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        contexts.add(context);
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.addBeanFactoryPostProcessor(beanFactory -> {
            beanFactory.registerSingleton("authController", controller);
            beanFactory.registerSingleton("exceptionHandler", new GlobalExceptionHandler());
            beanFactory.addEmbeddedValueResolver(value -> value
                    .replace("${auth.cookie.refresh-token.name}", refreshCookieName)
                    .replace("${auth.cookie.access-token.name}", "access_token"));
        });
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private AuthController configuredController(AuthService service, String refreshPath) {
        AuthController controller = new AuthController(service);
        ReflectionTestUtils.setField(controller, "accessTokenName", "access_token");
        ReflectionTestUtils.setField(controller, "refreshTokenName", "custom_refresh");
        ReflectionTestUtils.setField(controller, "accessTokenPath", "/");
        ReflectionTestUtils.setField(controller, "refreshTokenPath", refreshPath);
        ReflectionTestUtils.setField(controller, "accessTokenMaxAge", 900L);
        ReflectionTestUtils.setField(controller, "refreshTokenMaxAge", 86400L);
        ReflectionTestUtils.setField(controller, "accessTokenSameSite", "Lax");
        ReflectionTestUtils.setField(controller, "refreshTokenSameSite", "Lax");
        return controller;
    }

    private AuthResponse tokens() {
        return AuthResponse.builder().accessToken("access-token").refreshToken("refresh-token").build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {
    }
}
