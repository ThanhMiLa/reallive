package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.CreateSessionCommand;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.SessionCreationResult;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.Optional;
import java.util.UUID;

/**
 * Persists session lifecycle changes; media I/O belongs to the synchronous application use case.
 */
public interface StreamSessionDomainService {

    /**
     * Reserves a READY/OPEN session and its initial history, or returns the existing creation unchanged.
     *
     * <p>Deduplicates by owner and request ID, including concurrent calls when this method owns the
     * transaction. Joins an existing transaction so the application use case can provision MediaMTX
     * before committing. OPEN is provisional until that transaction commits; this method does not
     * create a media path or issue credentials. When joining a transaction, a database uniqueness
     * failure must be handled by the caller after the enclosing transaction has rolled back.</p>
     *
     * @param command owner, channel, streamer, category, title and creation request identifier
     * @return the reserved or existing session; created is true only for a new reservation
     * @throws AppException if another request occupies the channel slot or this request ID belongs to another channel
     * @throws DataIntegrityViolationException if a non-creation constraint fails, or a uniqueness failure
     *                                        must propagate through an enclosing transaction
     */
    SessionCreationResult createOrGetSession(CreateSessionCommand command);

    /**
     * Applies an ONLINE event whose actual source has been verified by the caller against MediaMTX.
     *
     * <p>Locks the session and persists state/history atomically. Keeps the first startedAt, ignores
     * duplicate events and terminal ENDED sessions, and rejects reconnect events outside the stored
     * grace window. An expired online event never marks the path CLOSED; closure must be confirmed
     * by the synchronous expiry use case.</p>
     *
     * @param command verified source details, event identifier and optional occurrence time in UTC
     * @return the updated session, or its unchanged state when the event is ignored
     * @throws AppException if the session does not exist
     */
    StreamSessionEntity onMediaOnline(MediaOnlineCommand command);

    /**
     * Applies an OFFLINE event for the current source after the caller has checked the media state.
     *
     * <p>Locks the session and records RECONNECTING with a fixed reconnect deadline. Duplicate events
     * do not extend that deadline; an obsolete source cannot disconnect a newer active source.</p>
     *
     * @param command verified offline source and event details
     * @return the updated session, or its unchanged state for a duplicate, stale or terminal event
     * @throws AppException if the session does not exist
     */
    StreamSessionEntity onMediaOffline(MediaOfflineCommand command);

    /**
     * Commits the domain mutation for an expired reconnect after media closure has been confirmed.
     *
     * <p>Requires an existing transaction. The caller must lock and recheck the session and confirm
     * path closure within that transaction before calling this method. Rechecks the persisted
     * deadline, records ENDED/CLOSED and history together, and creates no background task.</p>
     *
     * @param streamId the session whose reconnect deadline should be checked
     * @return the ended session, or empty if absent, no longer reconnecting or not yet due
     * @throws IllegalTransactionStateException if no enclosing transaction exists
     */
    Optional<StreamSessionEntity> expireReconnectIfDue(UUID streamId);

    /**
     * Applies an owner's End after the synchronous use case has confirmed media path closure.
     *
     * <p>Requires the caller to hold the session lock in an existing transaction, check ownership
     * before media I/O and confirm closure before this mutation. Records ENDED/CLOSED and history
     * atomically; repeating End leaves endedAt and history unchanged. Does not call MediaMTX.</p>
     *
     * @param streamId the session to terminate
     * @param ownerUserId the authenticated owner user identifier
     * @param reason optional history reason, at most 500 characters
     * @return the ended session, including an already terminal session
     * @throws AppException if the session does not exist or belongs to another owner
     * @throws IllegalTransactionStateException if no enclosing transaction exists
     */
    StreamSessionEntity endSession(UUID streamId, UUID ownerUserId, String reason);
}
