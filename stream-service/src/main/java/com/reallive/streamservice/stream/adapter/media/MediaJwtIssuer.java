package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.stream.dto.response.StreamPublishCredentialsResponse;

import java.util.Map;
import java.util.UUID;

/**
 * Issues signed MediaMTX JWTs for publishing, playback, media worker reads and control API calls.
 *
 * <p>Every token is signed with the configured RS256 key and shares the configured issuer,
 * audience and key ID. A token grants only the permission required by its caller. This contract
 * does not validate sessions, authorize ownership or persist any state.</p>
 */
public interface MediaJwtIssuer {

    /**
     * Issues a playback token scoped to a session's exact media path.
     *
     * <p>The token subject is the viewer user ID and the only granted permission is read access
     * to {@code live/<sessionId>}. Its lifetime is the configured read token TTL.</p>
     *
     * @param sessionId non-null session UUID identifying the exact media path
     * @param viewerUserId non-null viewer user ID used as the token subject
     * @return the signed token and its expiration instant
     * @throws NullPointerException if sessionId or viewerUserId is null
     * @throws IllegalStateException if the token cannot be signed
     */
    MediaToken issueRead(UUID sessionId, UUID viewerUserId);

    /**
     * Issues a read-only token for the media worker technical identity.
     *
     * <p>The token subject is {@code media-worker} and the only granted permission is read access
     * to {@code live/<sessionId>}. Its lifetime is the configured read token TTL.</p>
     *
     * @param sessionId non-null session UUID identifying the exact media path
     * @return the signed token and its expiration instant
     * @throws NullPointerException if sessionId is null
     * @throws IllegalStateException if the token cannot be signed
     */
    MediaToken issueWorkerRead(UUID sessionId);

    /**
     * Issues a control API token for the Stream Service technical identity.
     *
     * <p>The token subject is {@code stream-service} and the only granted permission is access to
     * the MediaMTX control API. Its lifetime is the configured control token TTL.</p>
     *
     * @return the signed token and its expiration instant
     * @throws IllegalStateException if the token cannot be signed
     */
    MediaToken issueControlApi();

    /**
     * Builds RTMP publish credentials for a session owner.
     *
     * <p>The stream key combines the session ID with a newly issued publish token. The response
     * contains credentials and must not be logged in clear text.</p>
     *
     * @param sessionId non-null session UUID identifying the exact media path
     * @param ownerUserId non-null owner user ID used as the publish token subject
     * @return credentials containing the configured RTMP base URL, session-scoped stream key and
     *         publish token expiration instant
     * @throws NullPointerException if sessionId or ownerUserId is null
     * @throws IllegalStateException if the publish token cannot be signed
     */
    StreamPublishCredentialsResponse publishCredentials(UUID sessionId, UUID ownerUserId);

    /**
     * Returns the public JSON Web Key Set used to verify issued tokens.
     *
     * <p>The set exposes only the public parameters of the active RS256 signing key.</p>
     *
     * @return a JWKS document containing the public signing key
     */
    Map<String, Object> publicJwks();
}
