package com.reallive.apigateway.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("gateway.security")
public record GatewaySecurityProperties(
        String signerKey,
        String issuer,
        Duration blacklistTimeout,
        List<String> allowedOrigins,
        TokenCookie accessCookie,
        TokenCookie refreshCookie,
        @DefaultValue("true") boolean csrfEnabled) {

    public GatewaySecurityProperties {
        Assert.hasText(issuer, "JWT issuer is required");
        Assert.isTrue(blacklistTimeout != null && blacklistTimeout.isPositive(), "Blacklist timeout must be positive");
        Assert.notEmpty(allowedOrigins, "At least one allowed origin is required");
        Assert.notNull(accessCookie, "Access cookie configuration is required");
        Assert.notNull(refreshCookie, "Refresh cookie configuration is required");
        Assert.isTrue(!accessCookie.name().equals(refreshCookie.name()), "Token cookie names must be different");
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    public record TokenCookie(String name, String path, boolean secure, boolean httpOnly, String sameSite) {

        public TokenCookie {
            Assert.hasText(name, "Token cookie name is required");
            Assert.isTrue(path != null && path.startsWith("/"), "Cookie path must be absolute");
            Assert.hasText(sameSite, "Cookie SameSite is required");
        }
    }
}
