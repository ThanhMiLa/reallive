package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;

import java.util.Optional;
import java.util.UUID;

/**
 * Provides synchronous media path and publisher operations through the MediaMTX Control API.
 *
 * <p>Session operations derive an exact path from the session UUID. Requests use backend-only API
 * credentials and finite timeouts; failures are not retried in the background. This adapter performs
 * media I/O without authorizing session ownership or changing application database state.</p>
 */
public interface MediaServerAdapter {

    /**
     * Reads the configuration of a session's exact media path without creating or changing it.
     *
     * <p>A configured path does not imply that a publisher is online; use {@link #getSource(UUID)}
     * to inspect the runtime source.</p>
     *
     * @param sessionId non-null session UUID identifying the exact media path
     * @return the path configuration, or empty when MediaMTX confirms that the configuration is absent
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if the request fails,
     *                      times out or returns an invalid response
     * @throws NullPointerException if sessionId is null
     */
    Optional<MediaPathConfiguration> getPath(UUID sessionId);

    /**
     * Ensures that a session's exact path exists and accepts a publisher without publisher override.
     *
     * <p>Creates the path only when absent and verifies the resulting configuration. Repeated calls
     * preserve an existing compatible path and its live source. An incompatible configuration is
     * rejected rather than replaced; concurrent creation is accepted only after verifying the path.</p>
     *
     * @param sessionId non-null session UUID identifying the exact media path
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if provisioning or
     *                      verification fails, the configuration is incompatible or a request times out
     * @throws NullPointerException if sessionId is null
     */
    void ensurePath(UUID sessionId);

    /**
     * Deletes the configuration of a session's exact media path synchronously.
     *
     * <p>Repeating deletion is successful when MediaMTX confirms that the path is already absent.
     * This operation does not independently verify runtime source disconnection; callers requiring
     * closure confirmation must also inspect {@link #getSource(UUID)}.</p>
     *
     * @param sessionId non-null session UUID identifying the exact media path
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if deletion fails,
     *                      times out or returns an unrecognized response
     * @throws NullPointerException if sessionId is null
     */
    void deletePath(UUID sessionId);

    /**
     * Observes the runtime path's online flag and publisher connection details without changing them.
     *
     * <p>An offline path can have no source connection. An absent runtime path does not establish
     * whether its configuration exists; use {@link #getPath(UUID)} for that distinction.</p>
     *
     * @param sessionId non-null session UUID identifying the exact media path
     * @return the runtime observation, or empty when MediaMTX confirms that the runtime path is absent
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if the request fails,
     *                      times out or returns invalid or unsupported source details
     * @throws NullPointerException if sessionId is null
     */
    Optional<MediaPathStatus> getSource(UUID sessionId);

    /**
     * Retrieves one page of configured media paths, including paths without an online publisher.
     *
     * <p>Results describe configuration rather than runtime availability. The caller controls page
     * traversal; this operation does not reconcile paths or modify their configuration.</p>
     *
     * @param page zero-based page index
     * @param size requested number of items per page, from 1 through 100
     * @return a configuration page with total item count, total page count and returned items
     * @throws IllegalArgumentException if page is negative or size is outside the supported range
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if the request fails,
     *                      times out or returns an invalid page
     */
    MediaPage<MediaPathConfiguration> listConfigurations(int page, int size);

    /**
     * Retrieves one page of runtime media paths with their online flags and source connection details.
     *
     * <p>Results are runtime observations rather than a list of all configured paths. This operation
     * does not change media paths or application session states.</p>
     *
     * @param page zero-based page index
     * @param size requested number of items per page, from 1 through 100
     * @return a runtime page with total item count, total page count and returned observations
     * @throws IllegalArgumentException if page is negative or size is outside the supported range
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if the request fails,
     *                      times out or returns an invalid page or unsupported source details
     */
    MediaPage<MediaPathStatus> listPaths(int page, int size);

    /**
     * Disconnects a media connection through the endpoint matching its RTMP or RTMPS source type.
     *
     * <p>Uses the actual MediaMTX connection UUID, not the application session UUID. An already
     * absent connection is treated as disconnected. Path configuration is preserved, so an
     * authorized publisher can reconnect while the path remains available.</p>
     *
     * @param sourceType non-null RTMP or RTMPS connection type selecting the protocol endpoint
     * @param connectionId non-null MediaMTX connection UUID obtained from a runtime source observation
     * @throws AppException with {@link ErrorCode#MEDIA_SERVER_UNAVAILABLE} if disconnection fails,
     *                      times out or returns an unrecognized response
     * @throws NullPointerException if sourceType or connectionId is null
     */
    void kick(MediaSourceType sourceType, UUID connectionId);
}
