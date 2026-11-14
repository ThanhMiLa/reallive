package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPage;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.dto.MediaServerInfo;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;

import java.util.Optional;
import java.util.UUID;

/**
 * Provides synchronous media path, server info, and publisher operations through the MediaMTX
 * Control API.
 */
public interface MediaServerAdapter {

    /**
     * Reads runtime server identity and startup timestamp from MediaMTX {@code /v3/info}.
     *
     * @return current server info
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if unavailable or
     *     invalid
     */
    MediaServerInfo getServerInfo();

    /**
     * Reads the configuration of an ingest path {@code ingest/<ingestKeyId>}.
     *
     * @param ingestKeyId non-null ingest key UUID
     * @return configuration if present on MediaMTX, or empty when confirmed absent
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if Control API fails
     */
    Optional<MediaPathConfiguration> getIngestPath(UUID ingestKeyId);

    /**
     * Ensures that an ingest path {@code ingest/<ingestKeyId>} exists accepting publishers without
     * override and disabling viewer read hooks on the ingest namespace.
     *
     * @param ingestKeyId non-null ingest key UUID
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if provisioning or
     *     verification fails
     */
    void ensureIngestPath(UUID ingestKeyId);

    /**
     * Deletes an ingest path {@code ingest/<ingestKeyId>}; succeeds idempotently when already
     * absent.
     *
     * @param ingestKeyId non-null ingest key UUID
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if deletion fails
     */
    void deleteIngestPath(UUID ingestKeyId);

    /**
     * Observes the runtime status of {@code ingest/<ingestKeyId>}.
     *
     * @param ingestKeyId non-null ingest key UUID
     * @return runtime observation, or empty when confirmed absent
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if Control API fails
     */
    Optional<MediaPathStatus> getIngestSource(UUID ingestKeyId);

    /**
     * Reads the configuration of a playback path {@code live/<sessionId>}.
     *
     * @param sessionId non-null session UUID
     * @return configuration if present on MediaMTX, or empty when confirmed absent
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if Control API fails
     */
    Optional<MediaPathConfiguration> getPlaybackPath(UUID sessionId);

    /**
     * Ensures that playback path {@code live/<sessionId>} exists pulling internally via RTMP from
     * {@code ingest/<ingestKeyId>} using the supplied technical relay Read JWT, with {@code
     * sourceOnDemand: false} and lifecycle hooks disabled.
     *
     * @param sessionId non-null playback session UUID
     * @param ingestKeyId non-null source ingest key UUID
     * @param relayReadJwt non-blank internal relay Read JWT
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if provisioning or
     *     verification fails
     */
    void ensurePlaybackPath(UUID sessionId, UUID ingestKeyId, String relayReadJwt);

    /**
     * Deletes playback path {@code live/<sessionId>}; succeeds idempotently when already absent.
     *
     * @param sessionId non-null session UUID
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if deletion fails
     */
    void deletePlaybackPath(UUID sessionId);

    /**
     * Observes the runtime status of {@code live/<sessionId>}.
     *
     * @param sessionId non-null session UUID
     * @return runtime observation, or empty when confirmed absent
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if Control API fails
     */
    Optional<MediaPathStatus> getPlaybackSource(UUID sessionId);

    /**
     * Retrieves one page of configured media paths.
     *
     * @param page zero-based page index
     * @param size requested page size (1..100)
     * @return configuration page
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if Control API fails
     */
    MediaPage<MediaPathConfiguration> listConfigurations(int page, int size);

    /**
     * Retrieves one page of runtime media paths.
     *
     * @param page zero-based page index
     * @param size requested page size (1..100)
     * @return runtime status page
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if Control API fails
     */
    MediaPage<MediaPathStatus> listPaths(int page, int size);

    /**
     * Disconnects a publisher RTMP/RTMPS connection by its MediaMTX connection UUID.
     *
     * @param sourceType non-null publisher connection type (RTMP_CONN or RTMPS_CONN)
     * @param connectionId non-null MediaMTX connection UUID
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if Control API fails
     */
    void kick(MediaSourceType sourceType, UUID connectionId);
}
