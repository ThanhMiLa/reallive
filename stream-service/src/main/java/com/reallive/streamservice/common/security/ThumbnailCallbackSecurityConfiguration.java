package com.reallive.streamservice.common.security;

import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "stream.thumbnail", name = "callback-enabled", havingValue = "true")
public class ThumbnailCallbackSecurityConfiguration {

    ThumbnailProperties properties;
    JwtAuthenticationEntryPoint authenticationEntryPoint;
    ObjectMapper objectMapper;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain thumbnailCallbackFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/api/thumbnail-results")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTERNAL"))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(
                        new ThumbnailCallbackTokenFilter(properties.getCallbackInternalToken(), objectMapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
