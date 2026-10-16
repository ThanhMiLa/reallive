package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;

import java.util.List;

public interface AuthStateStore {

    /**
     * Publishes a committed auth snapshot without overwriting a newer revision.
     * Revocations and publication succeed atomically; an already consumed token rejects the operation.
     *
     * @param snapshot the committed authorities, status, revision and minimum TTL
     * @param revocations tokens consumed by this publication; empty for ordinary synchronization
     * @param issuingTokens whether a banned current account must reject publication
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} for a consumed token,
     *         {@link ErrorCode#USER_NOT_ACTIVE} for banned issuance, or
     *         {@link ErrorCode#AUTH_STATE_UNAVAILABLE} when publication cannot complete
     */
    void publish(AuthStateSnapshot snapshot, List<TokenRevocation> revocations, boolean issuingTokens);

    /**
     * Atomically revokes the supplied tokens, rejecting an already revoked token.
     *
     * @param revocations unexpired tokens and their remaining lifetimes
     * @throws AppException with {@link ErrorCode#UNAUTHENTICATED} for a consumed token,
     *         or {@link ErrorCode#AUTH_STATE_UNAVAILABLE} when Redis is unavailable
     */
    void revoke(List<TokenRevocation> revocations);
}
