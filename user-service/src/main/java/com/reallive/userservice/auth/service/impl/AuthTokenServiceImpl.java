package com.reallive.userservice.auth.service.impl;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.adapter.redis.AuthStateSnapshot;
import com.reallive.userservice.auth.adapter.redis.AuthStateStore;
import com.reallive.userservice.auth.adapter.redis.TokenRevocation;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.service.AuthTokenService;
import com.reallive.userservice.auth.util.UserAuthoritiesMapper;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.security.TokenIssuancePolicy;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthTokenServiceImpl implements AuthTokenService {

    AuthStateStore authStateStore;
    UserAuthoritiesMapper authoritiesMapper;
    TokenIssuancePolicy issuancePolicy;
    ObjectMapper objectMapper;

    @NonFinal
    @Value("${jwt.signer-key}")
    String signerKey;

    @NonFinal
    @Value("${jwt.ttl_accessToken}")
    long accessTokenTtl;

    @NonFinal
    @Value("${jwt.ttl_refreshToken}")
    long refreshTokenTtl;

    @NonFinal
    @Value("${auth.state.ttl-grace:60s}")
    Duration ttlGrace;

    @PostConstruct
    void validateConfiguration() {
        Assert.hasText(signerKey, "JWT signer key is required");
        Assert.isTrue(signerKey.getBytes(StandardCharsets.UTF_8).length >= 64, "HS512 requires at least 64 UTF-8 key bytes");
        Assert.isTrue(accessTokenTtl > 0 && refreshTokenTtl >= accessTokenTtl,
                "Refresh token TTL must cover the positive access token TTL");
        Assert.isTrue(ttlGrace != null && !ttlGrace.isNegative(), "Auth-state TTL grace must be nonnegative");
    }

    @Override
    public AuthResponse issue(UserEntity user, List<TokenRevocation> revocations) {
        issuancePolicy.requireEligible(user);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        AuthResponse response = AuthResponse.builder()
                .accessToken(sign(user.getId(), now, false))
                .refreshToken(sign(user.getId(), now, true))
                .build();

        AuthStateSnapshot snapshot = new AuthStateSnapshot(
                user.getId(),
                user.getAuthRevision(),
                objectMapper.writeValueAsString(authoritiesMapper.map(user)),
                user.getStatus().name(),
                Duration.ofSeconds(refreshTokenTtl).plus(ttlGrace));

        Runnable publish = () -> authStateStore.publish(snapshot, revocations, true);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
        return response;
    }

    @Override
    public UUID refreshUserId(String token) {
        return UUID.fromString(claims(token, "REFRESH").getSubject());
    }

    @Override
    public List<TokenRevocation> revocation(String token, String type) {
        JWTClaimsSet claims = claims(token, type);
        Duration ttl = Duration.between(Instant.now(), claims.getExpirationTime().toInstant());
        if (ttl.isNegative() || ttl.isZero()) {
            if ("ACCESS".equals(type)) {
                return List.of();
            }
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return List.of(new TokenRevocation("auth:revoked:" + type.toLowerCase(Locale.ROOT) + ":" + claims.getJWTID(), ttl));
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        List<TokenRevocation> revocations = new ArrayList<>(revocation(accessToken, "ACCESS"));
        revocations.addAll(revocation(refreshToken, "REFRESH"));
        authStateStore.revoke(revocations);
    }

    private String sign(UUID userId, Instant issuedAt, boolean refresh) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer("reallive.com")
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plusSeconds(refresh ? refreshTokenTtl : accessTokenTtl)))
                .jwtID(UUID.randomUUID().toString())
                .claim("type", refresh ? "REFRESH" : "ACCESS").build();
        JWSObject token = new JWSObject(new JWSHeader(JWSAlgorithm.HS512), new Payload(claims.toJSONObject()));
        try {
            token.sign(new MACSigner(signerKey.getBytes(StandardCharsets.UTF_8)));
            return token.serialize();
        } catch (JOSEException exception) {
            throw new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
    }

    private JWTClaimsSet claims(String token, String type) {
        if (token == null || token.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            SignedJWT signed = SignedJWT.parse(token);
            JWTClaimsSet claims = signed.getJWTClaimsSet();
            Instant now = Instant.now();
            if (!JWSAlgorithm.HS512.equals(signed.getHeader().getAlgorithm())
                    || !signed.verify(new MACVerifier(signerKey.getBytes(StandardCharsets.UTF_8)))
                    || !"reallive.com".equals(claims.getIssuer()) || !type.equals(claims.getStringClaim("type"))
                    || claims.getJWTID() == null || claims.getJWTID().isBlank() || claims.getIssueTime() == null
                    || claims.getIssueTime().toInstant().isAfter(now) || claims.getExpirationTime() == null
                    || !claims.getExpirationTime().after(claims.getIssueTime())
                    || claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now)
                    || "REFRESH".equals(type) && !claims.getExpirationTime().toInstant().isAfter(now)) {
                throw new AppException(ErrorCode.UNAUTHENTICATED);
            }
            UUID.fromString(claims.getSubject());
            return claims;
        } catch (ParseException | JOSEException | IllegalArgumentException | NullPointerException exception) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
