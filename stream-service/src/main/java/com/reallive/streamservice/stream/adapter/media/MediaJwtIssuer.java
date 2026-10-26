package com.reallive.streamservice.stream.adapter.media;

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
import com.reallive.streamservice.stream.dto.response.StreamPublishCredentialsResponse;
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
public class MediaJwtIssuer {
    MediaKeyMaterial keys;
    MediaProperties properties;
    Clock clock;

    public MediaToken issuePublish(UUID sessionId, UUID ownerUserId) {
        return issue(Objects.requireNonNull(ownerUserId).toString(), properties.getPublishTokenTtl(),
                Map.of("action", "publish", "path", path(sessionId)));
    }

    public MediaToken issueRead(UUID sessionId, UUID viewerUserId) {
        return issue(Objects.requireNonNull(viewerUserId).toString(), properties.getReadTokenTtl(),
                Map.of("action", "read", "path", path(sessionId)));
    }

    public MediaToken issueControlApi() {
        return issue("stream-service", properties.getControlTokenTtl(), Map.of("action", "api"));
    }

    public StreamPublishCredentialsResponse publishCredentials(UUID sessionId, UUID ownerUserId) {
        MediaToken token = issuePublish(sessionId, ownerUserId);
        return new StreamPublishCredentialsResponse(sessionId, properties.getRtmpPublicUrl(),
                sessionId + "?token=" + token.value(), token.expiresAt());
    }

    public Map<String, Object> publicJwks() {
        var jwk = new RSAKey.Builder(keys.publicKey()).keyID(properties.getKeyId())
                .algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).build();
        return Map.of("keys", List.of(jwk.toPublicJWK().toJSONObject()));
    }

    private String path(UUID sessionId) {
        return "live/" + Objects.requireNonNull(sessionId);
    }

    private MediaToken issue(String subject, Duration ttl, Map<String, String> permission) {
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = issued.plus(ttl);
        var claims = new JWTClaimsSet.Builder().issuer(properties.getIssuer()).audience(properties.getAudience())
                .subject(subject).issueTime(Date.from(issued)).expirationTime(Date.from(expires))
                .jwtID(UUID.randomUUID().toString()).claim("mediamtx_permissions", List.of(permission)).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(properties.getKeyId()).build(), claims);
        try {
            token.sign(new RSASSASigner(keys.privateKey()));
            return new MediaToken(token.serialize(), expires);
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign media JWT");
        }
    }
}
