package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.SessionProvisioningResult;
import com.reallive.streamservice.stream.dto.request.CreateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.request.UpdateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.response.StreamPublishCredentialsResponse;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;

import java.util.UUID;

/** Application entry points own their transactions and must be invoked without an ambient transaction. */
public interface StreamSessionService {

    /**
     * Reserves and provisions a PUBLIC session synchronously, returning credentials only after commit.
     * Repeated owner/request identifiers return the original session without changing its metadata.
     * Media failures roll back DB changes; DB and MediaMTX do not share an atomic transaction.
     *
     * @param ownerUserId authenticated account identifier
     * @param request validated creation metadata and idempotency identifier
     * @return the committed session and credentials, with created true only for the first creation
     * @throws AppException if eligibility fails, the channel slot is occupied, the session cannot publish or media fails
     */
    SessionProvisioningResult create(UUID ownerUserId, CreateStreamSessionRequest request);

    /**
     * Updates only the supplied title/category of an owned, nonterminal session without media I/O.
     * Category validation precedes the write transaction; ownership/state are rechecked under a row lock.
     *
     * @param sessionId session to update
     * @param ownerUserId authenticated account identifier
     * @param request validated partial update; omitted fields remain unchanged
     * @return committed owner metadata without credentials
     * @throws AppException if ownership, status or category validation fails
     */
    StreamSessionResponse update(UUID sessionId, UUID ownerUserId, UpdateStreamSessionRequest request);

    /**
     * Issues a new publish credential for the same path after rechecking owner, eligibility and media readiness.
     * Does not persist tokens, revoke earlier credentials or restore missing media paths.
     *
     * @param sessionId session receiving the credential
     * @param ownerUserId authenticated account identifier
     * @return a fresh Publish JWT embedded in the OBS stream key and its expiry
     * @throws AppException if the session is absent, unowned, ended, beyond grace, ineligible or its path is unavailable
     */
    StreamPublishCredentialsResponse publishCredentials(UUID sessionId, UUID ownerUserId);

    /**
     * Returns the owner's active session, including a readiness observation and the persisted reconnect deadline.
     * An expired reconnect still occupies the channel until confirmed closure; no credential is returned.
     *
     * @param ownerUserId authenticated account identifier
     * @return active owner metadata, or null if there is no active session
     * @throws AppException if media readiness cannot be checked
     */
    StreamSessionResponse current(UUID ownerUserId);
}
