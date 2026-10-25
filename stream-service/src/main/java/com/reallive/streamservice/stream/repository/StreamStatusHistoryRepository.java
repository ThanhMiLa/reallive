package com.reallive.streamservice.stream.repository;

import com.reallive.streamservice.stream.entity.StreamStatusHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StreamStatusHistoryRepository extends JpaRepository<StreamStatusHistoryEntity, UUID> {

    List<StreamStatusHistoryEntity> findByStreamIdOrderByCreatedAtAscIdAsc(UUID streamId);

    boolean existsByMediaEventId(UUID mediaEventId);

    Optional<StreamStatusHistoryEntity> findByMediaEventId(UUID mediaEventId);
}
