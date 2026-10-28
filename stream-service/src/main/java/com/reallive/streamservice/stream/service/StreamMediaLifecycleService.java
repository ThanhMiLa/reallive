package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.SessionEventResult;

public interface StreamMediaLifecycleService {

    /**
     * Verifies the reported publisher source against MediaMTX under the session lock and transitions to LIVE.
     *
     * <p>Acquires a pessimistic write lock on the stream session. If the session is already ENDED,
     * the event is ignored without invoking MediaMTX. Otherwise, inspects the runtime path status on
     * MediaMTX to ensure the source is currently online and matches the reported connection identifier
     * and source type. On successful verification, applies the transition to LIVE atomically; stale,
     * duplicate or mismatched online events return an IGNORED outcome.</p>
     *
     * @param command verified source details, event identifier and UTC occurrence timestamp
     * @return the updated session entity and whether the event caused a state transition
     * @throws AppException if the session is not found or MediaMTX is unavailable
     */
    SessionEventResult onOnline(MediaOnlineCommand command);

    /**
     * Inspects the current media state under the session lock and transitions a LIVE session to RECONNECTING.
     *
     * <p>Acquires a pessimistic write lock on the stream session. If the session is already ENDED or READY,
     * the event is ignored. If MediaMTX indicates that a publisher source is currently online (for example,
     * when a rapid reconnection has already replaced the disconnected source), the offline event is
     * treated as stale and ignored. Otherwise, records RECONNECTING and a persisted reconnect deadline.</p>
     *
     * @param command verified offline source details and event identifier
     * @return the updated session entity and whether the event caused a state transition
     * @throws AppException if the session is not found or MediaMTX is unavailable
     */
    SessionEventResult onOffline(MediaOfflineCommand command);
}
