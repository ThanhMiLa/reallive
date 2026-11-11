package com.reallive.userservice.user.repository;

import com.reallive.userservice.user.entity.UserCategoryPreferenceEntity;
import jakarta.persistence.EntityManager;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserCategoryPreferenceInsertRepositoryImpl implements UserCategoryPreferenceInsertRepository {

    EntityManager entityManager;

    @Override
    @Transactional
    public void insertAll(List<UserCategoryPreferenceEntity> preferences) {
        preferences.forEach(entityManager::persist);
    }
}
