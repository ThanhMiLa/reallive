package com.reallive.streamservice.stream.adapter.media.impl;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.MediaToken;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaJwtIssuerImpl implements MediaJwtIssuer {

    MediaKeyMaterial keys;
    MediaProperties properties;
    Clock clock;

    @Override
    public MediaToken issuePublish(UUID ingestKeyId, UUID ownerUserId) {
        return issue(
                Objects.requireNonNull(ownerUserId).toString(),
                null,
                Map.of("action", "publish", "path", ingestPath(ingestKeyId)));
    }

    @Override
    public MediaToken issueRelayRead(UUID ingestKeyId) {
        return issue(
                "relay-internal", null, Map.of("action", "read", "path", ingestPath(ingestKeyId)));
    }

    @Override
    public MediaToken issueRead(UUID sessionId, UUID viewerUserId) {
        return issue(
                Objects.requireNonNull(viewerUserId).toString(),
                properties.getReadTokenTtl(),
                Map.of("action", "read", "path", playbackPath(sessionId)));
    }

    @Override
    public MediaToken issueWorkerRead(UUID sessionId) {
        return issue(
                "media-worker",
                properties.getReadTokenTtl(),
                Map.of("action", "read", "path", playbackPath(sessionId)));
    }

    @Override
    public Map<String, Object> publicJwks() {
        var jwk =
                new RSAKey.Builder(keys.publicKey())
                        .keyID(properties.getKeyId())
                        .algorithm(JWSAlgorithm.RS256)
                        .keyUse(KeyUse.SIGNATURE)
                        .build();
        return Map.of("keys", List.of(jwk.toPublicJWK().toJSONObject()));
    }

    private String ingestPath(UUID ingestKeyId) {
        return "ingest/" + Objects.requireNonNull(ingestKeyId);
    }

    private String playbackPath(UUID sessionId) {
        return "live/" + Objects.requireNonNull(sessionId);
    }

    private MediaToken issue(String subject, Duration ttl, Map<String, String> permission) {
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = ttl == null ? null : issued.plus(ttl);
        var claimsBuilder =
                new JWTClaimsSet.Builder()
                        .issuer(properties.getIssuer())
                        .audience(properties.getAudience())
                        .subject(subject)
                        .issueTime(Date.from(issued))
                        .jwtID(UUID.randomUUID().toString())
                        .claim("mediamtx_permissions", List.of(permission));
        if (expires != null) {
            claimsBuilder.expirationTime(Date.from(expires));
        }
        var token =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .type(JOSEObjectType.JWT)
                                .keyID(properties.getKeyId())
                                .build(),
                        claimsBuilder.build());
        try {
            token.sign(new RSASSASigner(keys.privateKey()));
            return new MediaToken(token.serialize(), expires);
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign media JWT");
        }
    }
}
