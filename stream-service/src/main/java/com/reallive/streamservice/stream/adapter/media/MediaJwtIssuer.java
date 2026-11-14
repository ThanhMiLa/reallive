package com.reallive.streamservice.stream.adapter.media;

import java.util.Map;
import java.util.UUID;

/**
 * Issues signed MediaMTX JWTs for persistent ingest publishing, internal RTMP relay, playback,
 * and media worker reads.
 */
public interface MediaJwtIssuer {

    /**
     * Issues a permanent RS256 Publish JWT (without {@code exp}) scoped strictly to {@code
     * ingest/<ingestKeyId>}.
     *
     * @param ingestKeyId non-null ingest key UUID
     * @param ownerUserId non-null channel owner user ID used as token subject
     * @return the signed permanent token with null expiration
     */
    MediaToken issuePublish(UUID ingestKeyId, UUID ownerUserId);

    /**
     * Issues a permanent RS256 internal relay read JWT (without {@code exp}) scoped strictly to
     * {@code ingest/<ingestKeyId>}.
     *
     * @param ingestKeyId non-null ingest key UUID
     * @return the signed internal relay read token with null expiration
     */
    MediaToken issueRelayRead(UUID ingestKeyId);

    /**
     * Issues a short-lived playback token scoped strictly to {@code live/<sessionId>}.
     *
     * @param sessionId non-null session UUID
     * @param viewerUserId non-null viewer user ID used as token subject
     * @return the signed token and its expiration instant
     */
    MediaToken issueRead(UUID sessionId, UUID viewerUserId);

    /**
     * Issues a short-lived read-only token for the media worker scoped strictly to {@code
     * live/<sessionId>}.
     *
     * @param sessionId non-null session UUID
     * @return the signed token and its expiration instant
     */
    MediaToken issueWorkerRead(UUID sessionId);

    /**
     * Formats the OBS stream key from an ingest key UUID and permanent publish JWT.
     *
     * @param ingestKeyId ingest key UUID
     * @param publishJwt raw publish JWT
     * @return formatted OBS stream key
     */
    default String formatStreamKey(UUID ingestKeyId, String publishJwt) {
        return ingestKeyId + "?token=" + publishJwt;
    }

    /**
     * Returns the public JSON Web Key Set used to verify issued tokens.
     *
     * @return a JWKS document containing the public signing key
     */
    Map<String, Object> publicJwks();
}
