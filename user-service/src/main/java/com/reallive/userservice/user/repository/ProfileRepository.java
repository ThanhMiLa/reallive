package com.reallive.userservice.user.repository;

import com.reallive.userservice.user.entity.ProfileEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfileRepository extends JpaRepository<ProfileEntity, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<ProfileEntity> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from ProfileEntity profile where profile.user.id = :userId")
    Optional<ProfileEntity> findByUserIdForUpdate(UUID userId);
}
