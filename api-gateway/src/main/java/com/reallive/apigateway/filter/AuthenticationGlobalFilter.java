package com.reallive.apigateway.filter;

import com.reallive.apigateway.security.CookieAuthenticationService;
import com.reallive.apigateway.security.GatewayErrorResponseWriter;
import com.reallive.apigateway.security.GatewaySecurityException;
import com.reallive.apigateway.security.TokenType;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final Set<String> PUBLIC_ENDPOINTS_GET = Set.of();
    private static final Set<String> PUBLIC_ENDPOINTS_POST = Set.of(
            "/user/api/auth/login",
            "/user/api/auth/register",
            "/user/api/auth/refresh",
            "/user/api/auth/logout",
            "/user/api/auth/forgot-password",
            "/user/api/auth/verify-reset-otp",
            "/user/api/auth/reset-password",
            "/notification/api/webhooks/sendgrid"
    );
    private static final Map<HttpMethod, Set<String>> PUBLIC_ENDPOINTS = Map.of(
            HttpMethod.GET, PUBLIC_ENDPOINTS_GET,
            HttpMethod.POST, PUBLIC_ENDPOINTS_POST
    );

    private final CookieAuthenticationService cookieAuthenticationService;
    private final GatewayErrorResponseWriter errorWriter;

    public AuthenticationGlobalFilter(CookieAuthenticationService cookieAuthenticationService,
                                      GatewayErrorResponseWriter errorWriter) {
        this.cookieAuthenticationService = cookieAuthenticationService;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return cookieAuthenticationService.verifyCookies(exchange)
                .then(Mono.defer(() -> requireEndpointToken(exchange)))
                .thenReturn(true)
                .onErrorResume(GatewaySecurityException.class,
                        exception -> errorWriter.write(exchange, exception).thenReturn(false))
                .flatMap(authorized -> authorized ? chain.filter(exchange) : Mono.empty());
    }

    private Mono<Void> requireEndpointToken(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();
        if (PUBLIC_ENDPOINTS.getOrDefault(method, Set.of()).contains(path)) {
            return Mono.empty();
        }
        return cookieAuthenticationService.hasCookie(exchange, TokenType.ACCESS)
                ? Mono.empty() : Mono.error(GatewaySecurityException.unauthorized(Set.of()));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
