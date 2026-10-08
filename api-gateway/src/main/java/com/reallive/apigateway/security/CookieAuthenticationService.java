package com.reallive.apigateway.security;

import com.reallive.apigateway.configuration.GatewaySecurityProperties;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class CookieAuthenticationService {

    private final GatewaySecurityProperties properties;
    private final JwtTokenVerifier verifier;

    public CookieAuthenticationService(GatewaySecurityProperties properties, JwtTokenVerifier verifier) {
        this.properties = properties;
        this.verifier = verifier;
    }

    public Mono<Void> verifyCookies(ServerWebExchange exchange) {
        return Mono.defer(() -> {
            if (exchange.getRequest().getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
                return Mono.error(new GatewaySecurityException(HttpStatus.BAD_REQUEST, 1012,
                        "Authorization header is not supported; use token cookies", Set.of()));
            }
            return Flux.just(TokenType.ACCESS, TokenType.REFRESH)
                    .concatMap(type -> verifyCookie(exchange, type))
                    .collectList()
                    .flatMap(this::validateResults);
        });
    }

    public boolean hasCookie(ServerWebExchange exchange, TokenType type) {
        return exchange.getRequest().getCookies().containsKey(cookieSettings(type).name());
    }

    private Mono<CheckedCookie> verifyCookie(ServerWebExchange exchange, TokenType type) {
        List<HttpCookie> cookies = exchange.getRequest().getCookies().get(cookieSettings(type).name());
        if (cookies == null) {
            return Mono.empty();
        }
        if (cookies.size() != 1 || cookies.getFirst().getValue().isBlank()) {
            return Mono.just(new CheckedCookie(type, null));
        }
        return verifier.verify(cookies.getFirst().getValue(), type)
                .map(jwt -> new CheckedCookie(type, jwt))
                .onErrorResume(JwtException.class, exception -> Mono.just(new CheckedCookie(type, null)));
    }

    private Mono<Void> validateResults(List<CheckedCookie> results) {
        Set<TokenType> invalidCookies = EnumSet.noneOf(TokenType.class);
        results.stream()
                .filter(result -> result.jwt() == null)
                .forEach(result -> invalidCookies.add(result.type()));
        if (!invalidCookies.isEmpty()) {
            return Mono.error(GatewaySecurityException.unauthorized(invalidCookies));
        }
        if (results.stream().map(result -> result.jwt().getSubject()).distinct().count() > 1) {
            return Mono.error(GatewaySecurityException.unauthorized(EnumSet.allOf(TokenType.class)));
        }
        return Mono.empty();
    }

    private GatewaySecurityProperties.TokenCookie cookieSettings(TokenType type) {
        return type == TokenType.ACCESS ? properties.accessCookie() : properties.refreshCookie();
    }

    private record CheckedCookie(TokenType type, Jwt jwt) {
    }
}
