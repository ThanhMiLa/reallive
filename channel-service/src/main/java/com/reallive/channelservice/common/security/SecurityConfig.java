package com.reallive.channelservice.common.security;

import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import jakarta.servlet.http.Cookie;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SecurityConfig {

    CustomJwtDecoder customJwtDecoder;
    JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    JwtAccessDeniedHandler jwtAccessDeniedHandler;

    private static final String[] PUBLIC_ENDPOINTS_GET = {
            "/api/channels",
            "/api/categories"
    };

    private static final String[] INTERNAL_ENDPOINTS_GET = {
            "/internal/api/channels/by-streamer/*/streaming-context"
    };

    private static final String[] INTERNAL_ENDPOINTS_POST = {
            "/internal/api/channels",
            "/internal/api/categories/validate",
            "/internal/api/channels/livestream-summaries",
            "/internal/api/channels/*/chat-access"
    };

    private static final String[] INTERNAL_ENDPOINTS_PATCH = {
            "/internal/api/channels/by-streamer/*/avatar"
    };

    RedisAuthoritiesReader authoritiesReader;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain internalEndpointFilterChain(HttpSecurity http) throws Exception {
        http.securityMatchers(matchers -> matchers
                        .requestMatchers(HttpMethod.GET, INTERNAL_ENDPOINTS_GET)
                        .requestMatchers(HttpMethod.POST, INTERNAL_ENDPOINTS_POST)
                        .requestMatchers(HttpMethod.PATCH, INTERNAL_ENDPOINTS_PATCH))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, BearerTokenResolver bearerTokenResolver) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new AuthStateFailureFilter(), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_ENDPOINTS_GET).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt
                                .decoder(customJwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                );

        return http.build();
    }

    @Bean
    BearerTokenResolver bearerTokenResolver(@Value("${auth.cookie.access-token.name}") String accessTokenName) {
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
