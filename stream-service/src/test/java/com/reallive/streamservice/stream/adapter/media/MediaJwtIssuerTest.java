package com.reallive.streamservice.stream.adapter.media;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import com.reallive.streamservice.stream.MediaTestSupport;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MediaJwtIssuerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);
    private final MediaJwtIssuer issuer = new MediaJwtIssuer(
            new MediaKeyMaterial((RSAPrivateKey) MediaTestSupport.KEYS.getPrivate(), (RSAPublicKey) MediaTestSupport.KEYS.getPublic()), MediaTestSupport.properties(), clock);

    @Test
    void publishAndReadHaveExactPathActionSubjectAndDifferentExpiry() throws Exception {
        UUID session = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        var publish = SignedJWT.parse(issuer.issuePublish(session, owner).value());
        var read = SignedJWT.parse(issuer.issueRead(session, viewer).value());
        assertTrue(publish.verify(new RSASSAVerifier((RSAPublicKey) MediaTestSupport.KEYS.getPublic())));
        assertEquals("RS256", publish.getHeader().getAlgorithm().getName());
        assertEquals("stream-rs256-v1", publish.getHeader().getKeyID());
        assertEquals("reallive-stream", publish.getJWTClaimsSet().getIssuer());
        assertEquals(List.of("reallive-media"), publish.getJWTClaimsSet().getAudience());
        assertEquals(owner.toString(), publish.getJWTClaimsSet().getSubject());
        assertEquals(viewer.toString(), read.getJWTClaimsSet().getSubject());
        assertEquals(List.of(Map.of("action", "publish", "path", "live/" + session)), publish.getJWTClaimsSet().getClaim("mediamtx_permissions"));
        assertEquals(List.of(Map.of("action", "read", "path", "live/" + session)), read.getJWTClaimsSet().getClaim("mediamtx_permissions"));
        assertEquals(clock.instant().plusSeconds(8 * 3600), publish.getJWTClaimsSet().getExpirationTime().toInstant());
        assertEquals(clock.instant().plusSeconds(300), read.getJWTClaimsSet().getExpirationTime().toInstant());
        assertNotEquals(publish.getJWTClaimsSet().getJWTID(), read.getJWTClaimsSet().getJWTID());
    }

    @Test
    void apiTokenHasNoClientPermissionAndCredentialsAreRedacted() throws Exception {
        var api = issuer.issueControlApi();
        var claims = SignedJWT.parse(api.value()).getJWTClaimsSet();
        assertEquals(List.of(Map.of("action", "api")), claims.getClaim("mediamtx_permissions"));
        assertEquals("stream-service", claims.getSubject());
        assertEquals(clock.instant().plusSeconds(300), claims.getExpirationTime().toInstant());
        assertFalse(api.toString().contains(api.value()));
        UUID id = UUID.randomUUID();
        var credentials = issuer.publishCredentials(id, UUID.randomUUID());
        assertTrue(credentials.streamKey().startsWith(id + "?token="));
        assertFalse(credentials.toString().contains(credentials.streamKey()));
    }

    @Test
    void jwksContainsOnlyPublicParametersAndVerifiesIssuedTokens() throws Exception {
        var set = JWKSet.parse(issuer.publicJwks());
        assertEquals(1, set.getKeys().size());
        assertFalse(set.getKeys().getFirst().isPrivate());
        var token = SignedJWT.parse(issuer.issuePublish(UUID.randomUUID(), UUID.randomUUID()).value());
        assertTrue(token.verify(new RSASSAVerifier(set.getKeys().getFirst().toRSAKey().toRSAPublicKey())));
        assertFalse(set.toJSONObject().toString().contains("private"));
    }

}
