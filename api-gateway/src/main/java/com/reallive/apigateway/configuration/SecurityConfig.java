package com.reallive.apigateway.configuration;

import com.reallive.apigateway.security.GatewayErrorResponseWriter;
import com.reallive.apigateway.security.GatewaySecurityException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, GatewaySecurityProperties properties,
                                                GatewayErrorResponseWriter errorWriter) {
        CookieServerCsrfTokenRepository csrfRepository = new CookieServerCsrfTokenRepository();
        csrfRepository.setCookieCustomizer(cookie -> cookie.path("/")
                .secure(properties.accessCookie().secure()).sameSite(properties.accessCookie().sameSite()));
        return http
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .csrf(csrf -> {
                    if (properties.csrfEnabled()) {
                        csrf.csrfTokenRepository(csrfRepository)
                                .requireCsrfProtectionMatcher(exchange -> {
                                    if (exchange.getRequest().getMethod() == HttpMethod.POST
                                            && "/notification/api/webhooks/sendgrid"
                                            .equals(exchange.getRequest().getPath().value())) {
                                        return ServerWebExchangeMatcher.MatchResult.notMatch();
                                    }
                                    return CsrfWebFilter.DEFAULT_CSRF_MATCHER.matches(exchange);
                                })
                                .accessDeniedHandler((exchange, exception) -> errorWriter.write(exchange,
                                        new GatewaySecurityException(HttpStatus.FORBIDDEN, 1002,
                                                "Invalid or missing CSRF token", Set.of())));
                    } else {
                        csrf.disable();
                    }
                })
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/actuator/**").denyAll()
                        // Routed endpoint authentication is enforced by AuthenticationGlobalFilter.
                        .anyExchange().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, exception) -> errorWriter.write(exchange,
                                GatewaySecurityException.unauthorized(Set.of())))
                        .accessDeniedHandler((exchange, exception) -> errorWriter.write(exchange,
                                new GatewaySecurityException(HttpStatus.FORBIDDEN, 1002, "Access Denied", Set.of()))))
                .build();
    }

    private UrlBasedCorsConfigurationSource corsConfigurationSource(GatewaySecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
