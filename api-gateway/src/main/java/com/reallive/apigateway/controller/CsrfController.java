package com.reallive.apigateway.controller;

import com.reallive.apigateway.common.ApiResponse;
import com.reallive.apigateway.security.CookieAuthenticationService;
import com.reallive.apigateway.security.GatewayErrorResponseWriter;
import com.reallive.apigateway.security.GatewaySecurityException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

@RestController
@ConditionalOnProperty(prefix = "gateway.security", name = "csrf-enabled", havingValue = "true", matchIfMissing = true)
public class CsrfController {

    private final CookieAuthenticationService authenticationService;
    private final GatewayErrorResponseWriter errorWriter;

    public CsrfController(CookieAuthenticationService authenticationService, GatewayErrorResponseWriter errorWriter) {
        this.authenticationService = authenticationService;
        this.errorWriter = errorWriter;
    }

    @GetMapping("/api/auth/csrf")
    public Mono<ResponseEntity<ApiResponse<CsrfResponse>>> csrf(ServerWebExchange exchange) {
        Mono<CsrfToken> csrfToken = exchange.getRequiredAttribute(CsrfToken.class.getName());
        return authenticationService.verifyCookies(exchange).then(csrfToken)
                .map(token -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new ApiResponse<>(200, 1000, "CSRF token generated",
                                new CsrfResponse(token.getHeaderName(), token.getToken()), Instant.now().toString())))
                .onErrorResume(GatewaySecurityException.class,
                        exception -> errorWriter.write(exchange, exception).then(Mono.empty()));
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
