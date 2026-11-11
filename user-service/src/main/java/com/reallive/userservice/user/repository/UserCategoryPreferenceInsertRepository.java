package com.reallive.userservice.user.repository;

import com.reallive.userservice.user.entity.UserCategoryPreferenceEntity;

import java.util.List;

public interface UserCategoryPreferenceInsertRepository {

    /**
     * Persists new preferences without merge existence checks for their assigned composite identifiers.
     */
    void insertAll(List<UserCategoryPreferenceEntity> preferences);
}
