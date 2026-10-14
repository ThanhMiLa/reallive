package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {

    List<CategoryEntity> findAllByStatus(CategoryStatus status);

    Optional<CategoryEntity> findByName(String name);

    Optional<CategoryEntity> findBySlug(String slug);

    boolean existsByName(String name);

    boolean existsBySlug(String slug);
}
