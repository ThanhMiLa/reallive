package com.reallive.userservice.auth.service;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;

import java.util.UUID;

public interface AuthStateSyncService {

    /**
     * Advances the account's auth revision and records durable synchronization in the current transaction.
     * Publishes the committed state synchronously after commit, retaining a retry task on failure.
     *
     * @param user the managed account locked by the caller before changing roles or status
     * @throws AppException with {@link ErrorCode#AUTH_STATE_UNAVAILABLE} when post-commit publication
     *         fails; the database mutation and durable retry task remain committed
     */
    void changed(UserEntity user);

    /**
     * Queues refreshed authorities for every account assigned the changed role.
     * Call in the transaction that changes that role's permissions.
     *
     * @param roleId the role whose permissions changed
     * @throws AppException with {@link ErrorCode#AUTH_STATE_UNAVAILABLE} when post-commit publication
     *         fails for an affected account; its retry task remains committed
     */
    void rolePermissionsChanged(UUID roleId);
}
