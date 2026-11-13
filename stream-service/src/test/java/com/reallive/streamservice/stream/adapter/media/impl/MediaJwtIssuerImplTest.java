package com.reallive.streamservice.stream.adapter.media.impl;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MediaJwtIssuerImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);
    private final MediaJwtIssuer issuer = new MediaJwtIssuerImpl(
            new MediaKeyMaterial(
                    (RSAPrivateKey) MediaTestSupport.KEYS.getPrivate(),
                    (RSAPublicKey) MediaTestSupport.KEYS.getPublic(),
                    new SecretKeySpec(Base64.getDecoder().decode(MediaTestSupport.CREDENTIAL_ENCRYPTION_KEY_BASE64), "AES")
            ),
            MediaTestSupport.properties(),
            clock
    );

    @Test
    void publishAndRelayReadHaveNoExpAndScopeStrictlyToIngestPath() throws Exception {
        UUID ingestKeyId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        var publishToken = issuer.issuePublish(ingestKeyId, owner);
        var relayToken = issuer.issueRelayRead(ingestKeyId);

        assertNull(publishToken.expiresAt());
        assertNull(relayToken.expiresAt());

        var publish = SignedJWT.parse(publishToken.value());
        var relay = SignedJWT.parse(relayToken.value());

        assertTrue(publish.verify(new RSASSAVerifier((RSAPublicKey) MediaTestSupport.KEYS.getPublic())));
        assertTrue(relay.verify(new RSASSAVerifier((RSAPublicKey) MediaTestSupport.KEYS.getPublic())));
        assertEquals("RS256", publish.getHeader().getAlgorithm().getName());
        assertEquals("stream-rs256-v1", publish.getHeader().getKeyID());
        assertEquals("reallive-stream", publish.getJWTClaimsSet().getIssuer());
        assertEquals(List.of("reallive-media"), publish.getJWTClaimsSet().getAudience());
        assertEquals(owner.toString(), publish.getJWTClaimsSet().getSubject());
        assertEquals("relay-internal", relay.getJWTClaimsSet().getSubject());
        assertNull(publish.getJWTClaimsSet().getExpirationTime());
        assertNull(relay.getJWTClaimsSet().getExpirationTime());

        assertEquals(List.of(Map.of("action", "publish", "path", "ingest/" + ingestKeyId)),
                publish.getJWTClaimsSet().getClaim("mediamtx_permissions"));
        assertEquals(List.of(Map.of("action", "read", "path", "ingest/" + ingestKeyId)),
                relay.getJWTClaimsSet().getClaim("mediamtx_permissions"));

        assertFalse(publishToken.toString().contains(publishToken.value()));
        assertFalse(relayToken.toString().contains(relayToken.value()));
        assertEquals(ingestKeyId + "?token=" + publishToken.value(),
                issuer.formatStreamKey(ingestKeyId, publishToken.value()));
    }

    @Test
    void readAndWorkerReadHaveFiveMinuteTtlAndScopeStrictlyToPlaybackPath() throws Exception {
        UUID session = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();

        var read = SignedJWT.parse(issuer.issueRead(session, viewer).value());
        var worker = SignedJWT.parse(issuer.issueWorkerRead(session).value());

        assertTrue(read.verify(new RSASSAVerifier((RSAPublicKey) MediaTestSupport.KEYS.getPublic())));
        assertTrue(worker.verify(new RSASSAVerifier((RSAPublicKey) MediaTestSupport.KEYS.getPublic())));
        assertEquals(viewer.toString(), read.getJWTClaimsSet().getSubject());
        assertEquals("media-worker", worker.getJWTClaimsSet().getSubject());
        assertEquals(List.of(Map.of("action", "read", "path", "live/" + session)),
                read.getJWTClaimsSet().getClaim("mediamtx_permissions"));
        assertEquals(List.of(Map.of("action", "read", "path", "live/" + session)),
                worker.getJWTClaimsSet().getClaim("mediamtx_permissions"));
        assertEquals(clock.instant().plusSeconds(300), read.getJWTClaimsSet().getExpirationTime().toInstant());
        assertEquals(clock.instant().plusSeconds(300), worker.getJWTClaimsSet().getExpirationTime().toInstant());
    }

    @Test
    void apiTokenHasFiveMinuteTtlAndJwksExposesOnlyPublicParameters() throws Exception {
        var api = issuer.issueControlApi();
        var claims = SignedJWT.parse(api.value()).getJWTClaimsSet();
        assertEquals(List.of(Map.of("action", "api")), claims.getClaim("mediamtx_permissions"));
        assertEquals("stream-service", claims.getSubject());
        assertEquals(clock.instant().plusSeconds(300), claims.getExpirationTime().toInstant());
        assertFalse(api.toString().contains(api.value()));

        var set = JWKSet.parse(issuer.publicJwks());
        assertEquals(1, set.getKeys().size());
        assertFalse(set.getKeys().getFirst().isPrivate());
        var token = SignedJWT.parse(issuer.issuePublish(UUID.randomUUID(), UUID.randomUUID()).value());
        assertTrue(token.verify(new RSASSAVerifier(set.getKeys().getFirst().toRSAKey().toRSAPublicKey())));
        assertFalse(set.toJSONObject().toString().contains("private"));
    }
}
