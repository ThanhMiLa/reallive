package com.reallive.userservice.user.repository;

import com.reallive.userservice.user.entity.ProfileEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfileRepository extends JpaRepository<ProfileEntity, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<ProfileEntity> findByUserId(UUID userId);
}
