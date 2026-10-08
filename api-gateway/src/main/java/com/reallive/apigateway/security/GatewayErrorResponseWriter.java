package com.reallive.apigateway.security;

import com.reallive.apigateway.common.ApiResponse;
import com.reallive.apigateway.configuration.GatewaySecurityProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

@Component
public class GatewayErrorResponseWriter {

    private final ObjectMapper objectMapper;
    private final GatewaySecurityProperties properties;

    public GatewayErrorResponseWriter(ObjectMapper objectMapper, GatewaySecurityProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Mono<Void> write(ServerWebExchange exchange, GatewaySecurityException exception) {
        var response = exchange.getResponse();
        response.setStatusCode(exception.getStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl(CacheControl.noStore());
        exception.getInvalidCookies().forEach(type -> clearCookie(exchange, type));
        ApiResponse<Void> body = new ApiResponse<>(exception.getStatus().value(), exception.getCode(),
                exception.getMessage(), null, Instant.now().toString());
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private void clearCookie(ServerWebExchange exchange, TokenType type) {
        var cookie = type == TokenType.ACCESS ? properties.accessCookie() : properties.refreshCookie();
        exchange.getResponse().addCookie(ResponseCookie.from(cookie.name(), "")
                .path(cookie.path())
                .secure(cookie.secure())
                .httpOnly(cookie.httpOnly())
                .sameSite(cookie.sameSite())
                .maxAge(Duration.ZERO)
                .build());
    }
}
