package com.reallive.userservice.common.security;

import com.reallive.userservice.common.adapter.redis.RedisAuthoritiesReader;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
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
import org.springframework.util.AntPathMatcher;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@AllArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS_GET = {

    };

    private static final String[] INTERNAL_ENDPOINTS_GET = {
            "/internal/api/streamers/by-user/*/active",
            "/internal/api/users/*/category-preferences"
    };

    private static final String[] PUBLIC_ENDPOINTS_POST = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/forgot-password",
            "/api/auth/verify-reset-otp",
            "/api/auth/reset-password"
    };

    private final CustomJwtDecoder customJwtDecoder;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    private final RedisAuthoritiesReader authoritiesReader;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, BearerTokenResolver bearerTokenResolver) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new AuthStateFailureFilter(), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, INTERNAL_ENDPOINTS_GET).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_ENDPOINTS_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_ENDPOINTS_POST).permitAll()
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
            if (isPublicRequest(request)) {
                return null;
            }
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

    private boolean isPublicRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        AntPathMatcher matcher = new AntPathMatcher();
        if ("GET".equals(method) && Arrays.stream(PUBLIC_ENDPOINTS_GET).anyMatch(pattern -> matcher.match(pattern, path))) {
            return true;
        }
        if ("POST".equals(method) && Arrays.stream(PUBLIC_ENDPOINTS_POST).anyMatch(pattern -> matcher.match(pattern, path))) {
            return true;
        }
        if ("GET".equals(method) && Arrays.stream(INTERNAL_ENDPOINTS_GET).anyMatch(pattern -> matcher.match(pattern, path))) {
            return true;
        }
        return false;
    }
}
