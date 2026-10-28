package com.reallive.userservice.auth.repository;

import com.reallive.userservice.auth.entity.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<UserEntity> findByUsername(String username);

    /**
     * Call before reading eligibility in a transaction; the user row stays locked until completion.
     * Load roles separately to avoid locking collection outer joins on PostgreSQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT user
            FROM UserEntity user
            WHERE user.id = :userId
            """)
    Optional<UserEntity> findByIdForUpdate(UUID userId);

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    @Query("""
            SELECT user
            FROM UserEntity user
            WHERE user.id = :userId
            """)
    Optional<UserEntity> findWithAuthoritiesById(UUID userId);

    @Query("""
            SELECT user.id
            FROM UserEntity user
            """)
    Page<UUID> findAllIds(Pageable pageable);

    @Query("""
            SELECT user.id
            FROM UserEntity user
            JOIN user.roles role
            WHERE role.id = :roleId
            """)
    Page<UUID> findIdsByRoleId(UUID roleId, Pageable pageable);

    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    Optional<UserEntity> findByEmail(String email);
}
