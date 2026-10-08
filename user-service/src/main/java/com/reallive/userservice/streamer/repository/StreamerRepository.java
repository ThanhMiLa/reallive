package com.reallive.userservice.streamer.repository;

import com.reallive.userservice.streamer.entity.StreamerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StreamerRepository extends JpaRepository<StreamerEntity, UUID> {

    Optional<StreamerEntity> findByUserId(UUID userId);
}
