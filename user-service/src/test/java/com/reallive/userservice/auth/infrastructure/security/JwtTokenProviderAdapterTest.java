package com.reallive.userservice.auth.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.reallive.userservice.auth.TestSigningKeys;
import com.reallive.userservice.auth.application.dto.response.TokenPair;
import com.reallive.userservice.auth.domain.model.User;
import com.reallive.userservice.auth.domain.model.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderAdapterTest {

    @Test
    void signsDistinctTokensWithSharedSessionFixedClaimsAndConfiguredLifetimes() {
        JwtTokenProviderAdapter provider = configuredProvider(TestSigningKeys.privateKey(), 420, 259200);
        User user = testUser();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestSigningKeys.publicKey()).build();

        Instant beforeIssue = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        TokenPair pair = provider.issue(user);
        Instant afterIssue = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Jwt access = decoder.decode(pair.accessToken());
        Jwt refresh = decoder.decode(pair.refreshToken());

        assertThat(access.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(access.getSubject()).isEqualTo(user.id().toString());
        assertThat(refresh.getSubject()).isEqualTo(user.id().toString());
        assertThat(access.getClaimAsString("iss")).isEqualTo("reallive-user-service");
        assertThat(refresh.getClaimAsString("iss")).isEqualTo("reallive-user-service");
        assertThat(access.getAudience()).containsExactly("reallive-api");
        assertThat(refresh.getAudience()).containsExactly("reallive-api");
        assertThat(access.getIssuedAt()).isBetween(beforeIssue, afterIssue);
        assertThat(refresh.getIssuedAt()).isEqualTo(access.getIssuedAt());
        assertThat(access.getExpiresAt()).isEqualTo(access.getIssuedAt().plus(Duration.ofMinutes(7)));
        assertThat(refresh.getExpiresAt()).isEqualTo(refresh.getIssuedAt().plus(Duration.ofDays(3)));
        assertThat(access.getClaimAsString("token_use")).isEqualTo("access");
        assertThat(refresh.getClaimAsString("token_use")).isEqualTo("refresh");
        assertThat(access.getClaimAsStringList("roles")).containsExactly("ADMIN", "USER");
        assertThat(refresh.hasClaim("roles")).isFalse();
        assertThat(UUID.fromString(access.getId())).isNotEqualTo(UUID.fromString(refresh.getId()));
        assertThat(UUID.fromString(access.getClaimAsString("sid")))
                .isEqualTo(UUID.fromString(refresh.getClaimAsString("sid")));
        Jwt anotherAccess = decoder.decode(provider.issue(user).accessToken());
        assertThat(anotherAccess.getClaimAsString("sid")).isNotEqualTo(access.getClaimAsString("sid"));

        String[] parts = pair.accessToken().split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A")
                + parts[2].substring(1);
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void invalidSigningKeyFailsWithoutIncludingSecretInError() {
        JwtTokenProviderAdapter provider = configuredProvider("not-a-private-key", 900, 2592000);
        assertThatThrownBy(() -> provider.issue(testUser()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not-a-private-key");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidTokenDurations(long ttl) {
        JwtTokenProviderAdapter invalidAccessTtl = configuredProvider(TestSigningKeys.privateKey(), ttl, 2592000);
        assertThatThrownBy(() -> invalidAccessTtl.issue(testUser()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.access-token.ttl");
        JwtTokenProviderAdapter invalidRefreshTtl = configuredProvider(TestSigningKeys.privateKey(), 900, ttl);
        assertThatThrownBy(() -> invalidRefreshTtl.issue(testUser()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.refresh-token.ttl");
    }

    @ParameterizedTest
    @ValueSource(strings = {"15m", "1.5", "invalid"})
    void springRejectsNonIntegerTtlConfiguration(String ttl) {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtTokenProviderAdapter.class)
                .withPropertyValues("jwt.signer-key=" + TestSigningKeys.privateKey(),
                        "auth.access-token.ttl=" + ttl, "auth.refresh-token.ttl=2592000")
                .run(context -> assertThat(context).hasFailed());
    }

    private User testUser() {
        return new User(UUID.randomUUID(), "alice", "alice@example.com", "hash", UserStatus.ACTIVE,
                false, Set.of("USER", "ADMIN"));
    }

    private JwtTokenProviderAdapter configuredProvider(String signerKey, long accessTtl, long refreshTtl) {
        JwtTokenProviderAdapter provider = new JwtTokenProviderAdapter();
        ReflectionTestUtils.setField(provider, "signerKey", signerKey);
        ReflectionTestUtils.setField(provider, "accessTokenTtlSeconds", accessTtl);
        ReflectionTestUtils.setField(provider, "refreshTokenTtlSeconds", refreshTtl);
        return provider;
    }
}
