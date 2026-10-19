package com.reallive.userservice.user.repository;

import com.reallive.userservice.user.entity.UserCategoryPreferenceEntity;
import com.reallive.userservice.user.entity.UserCategoryPreferenceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserCategoryPreferenceRepository extends JpaRepository<UserCategoryPreferenceEntity, UserCategoryPreferenceId> {

    @Query("SELECT p.id.categoryId " +
            "FROM UserCategoryPreferenceEntity p " +
            "WHERE p.id.userId = :userId " +
            "ORDER BY p.createdAt ASC")
    List<UUID> findCategoryIdsByUserId(@Param("userId") UUID userId);

    List<UserCategoryPreferenceEntity> findAllById_UserId(UUID userId);

    void deleteAllById_UserId(UUID userId);
}
