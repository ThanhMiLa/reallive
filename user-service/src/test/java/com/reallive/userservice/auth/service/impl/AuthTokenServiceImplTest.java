package com.reallive.userservice.auth.service.impl;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.adapter.redis.AuthStateSnapshot;
import com.reallive.userservice.auth.adapter.redis.AuthStateStore;
import com.reallive.userservice.auth.entity.PermissionEntity;
import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.util.UserAuthoritiesMapper;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.security.TokenIssuancePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthTokenServiceImplTest {

    private final AuthStateStore store = mock(AuthStateStore.class);
    private final AuthTokenServiceImpl issuer = new AuthTokenServiceImpl(store, new UserAuthoritiesMapper(),
            new TokenIssuancePolicy(), new ObjectMapper());
    private final UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(issuer, "signerKey", "test-auth-signing-secret-".repeat(4));
        ReflectionTestUtils.setField(issuer, "accessTokenTtl", 900L);
        ReflectionTestUtils.setField(issuer, "refreshTokenTtl", 3600L);
        ReflectionTestUtils.setField(issuer, "ttlGrace", Duration.ofSeconds(60));
    }

    @AfterEach
    void clearTransactionContext() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void unscopedTokenPairPublishesSortedDeduplicatedAuthoritiesAndRefreshLifetime() throws Exception {
        var permission = PermissionEntity.builder().name("CHANNEL_UPDATE").build();
        user.setRoles(Set.of(RoleEntity.builder().name("USER").permissions(Set.of(permission)).build(),
                RoleEntity.builder().name("STREAMER").permissions(Set.of(permission)).build()));
        var pair = issuer.issue(user, List.of());
        var access = SignedJWT.parse(pair.getAccessToken()).getJWTClaimsSet();
        var refresh = SignedJWT.parse(pair.getRefreshToken()).getJWTClaimsSet();
        assertNull(access.getClaim("scope"));
        assertNull(refresh.getClaim("scope"));
        assertEquals(user.getId().toString(), access.getSubject());
        assertEquals("ACCESS", access.getStringClaim("type"));
        assertEquals("REFRESH", refresh.getStringClaim("type"));
        assertNotEquals(access.getJWTID(), refresh.getJWTID());
        var snapshot = ArgumentCaptor.forClass(AuthStateSnapshot.class);
        verify(store).publish(snapshot.capture(), eq(List.of()), eq(true));
        assertEquals("[\"CHANNEL_UPDATE\",\"ROLE_STREAMER\",\"ROLE_USER\"]", snapshot.getValue().authorities());
        assertEquals("ACTIVE", snapshot.getValue().status());
        assertEquals(Duration.ofSeconds(3660), snapshot.getValue().ttl());
    }

    @Test
    void publicationIsDeferredUntilCommitAndRollbackDoesNotPublish() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        issuer.issue(user, List.of());
        verifyNoInteractions(store);
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(sync -> sync.afterCompletion(1));
        verifyNoInteractions(store);
    }

    @Test
    void successfulCommitPublishesBeforeTokensCanBeDelivered() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        issuer.issue(user, List.of());
        verifyNoInteractions(store);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(store).publish(any(), eq(List.of()), eq(true));
    }

    @Test
    void bannedAccountCannotObtainTokensOrOverwriteAuthState() {
        user.setStatus(UserStatus.BANNED);
        assertEquals(ErrorCode.USER_NOT_ACTIVE, assertThrows(AppException.class,
                () -> issuer.issue(user, List.of())).getErrorCode());
        verifyNoInteractions(store);
    }

    @Test
    void cacheFailurePreventsSuccessfulIssuanceAndRemains503() {
        doThrow(new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE)).when(store).publish(any(), anyList(), eq(true));
        assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, assertThrows(AppException.class,
                () -> issuer.issue(user, List.of())).getErrorCode());
    }

    @Test
    void validatesTokenTypeAndSignatureBeforeRefresh() {
        var pair = issuer.issue(user, List.of());
        assertEquals(user.getId(), issuer.refreshUserId(pair.getRefreshToken()));
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(AppException.class,
                () -> issuer.refreshUserId(pair.getAccessToken())).getErrorCode());
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(AppException.class,
                () -> issuer.refreshUserId("malformed")).getErrorCode());
    }
}
