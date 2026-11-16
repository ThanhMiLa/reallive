package com.reallive.userservice.user.repository;

import com.reallive.userservice.user.entity.ProfileEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProfileRepository extends JpaRepository<ProfileEntity, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<ProfileEntity> findByUserId(UUID userId);

    @EntityGraph(attributePaths = "user")
    List<ProfileEntity> findByUserIdIn(Collection<UUID> userIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT profile
            FROM ProfileEntity profile
            WHERE profile.user.id = :userId
            """)
    Optional<ProfileEntity> findByUserIdForUpdate(UUID userId);
}
