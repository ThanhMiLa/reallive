package com.reallive.streamservice.common.security;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SecurityConfig {

    private static final String[] INTERNAL_ENDPOINTS_GET = {
            "/internal/api/media/jwks",
            "/internal/api/streams/*/chat-context"
    };

    private static final String[] INTERNAL_ENDPOINTS_POST = {
            "/internal/api/media-events",
            "/internal/api/viewer-events",
            "/internal/api/thumbnail-results"
    };

    CustomJwtDecoder customJwtDecoder;
    JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    JwtAccessDeniedHandler jwtAccessDeniedHandler;
    RedisAuthoritiesReader authoritiesReader;
    ObjectMapper objectMapper;

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            BearerTokenResolver bearerTokenResolver,
            RedisJwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(
                        new AuthStateFailureFilter(objectMapper),
                        BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, INTERNAL_ENDPOINTS_GET).permitAll()
                                        .requestMatchers(HttpMethod.POST, INTERNAL_ENDPOINTS_POST).permitAll()
                                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                                        .accessDeniedHandler(jwtAccessDeniedHandler))
                .oauth2ResourceServer(
                        oauth2 -> oauth2.bearerTokenResolver(bearerTokenResolver)
                                        .jwt(jwt -> jwt
                                                .decoder(customJwtDecoder)
                                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                                        .accessDeniedHandler(jwtAccessDeniedHandler));

        return http.build();
    }

    @Bean
    BearerTokenResolver bearerTokenResolver(
            @Value("${auth.cookie.access-token.name}") String accessTokenName) {
        return request -> {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return null;
            }
            for (Cookie cookie : cookies) {
                if (accessTokenName.equals(cookie.getName())) {
                    String cookieToken = cookie.getValue();
                    if (cookieToken != null && !cookieToken.isBlank()) {
                        return cookieToken;
                    }
                }
            }
            return null;
        };
    }

    @Bean
    RedisJwtAuthenticationConverter jwtAuthenticationConverter() {
        return new RedisJwtAuthenticationConverter(authoritiesReader);
    }
}
