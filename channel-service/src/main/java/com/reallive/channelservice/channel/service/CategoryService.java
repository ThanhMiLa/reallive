package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.response.CategoryResponse;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface CategoryService {

    /**
     * Retrieves the list of all active categories registered in the system.
     *
     * <p>Returns an empty list when no active categories exist. The returned DTOs preserve category
     * identifiers, names, slugs, and thumbnail URLs for public display and selection.</p>
     *
     * @return a list containing all active categories mapped to {@link CategoryResponse}, or an empty list
     *         if no active categories are registered
     */
    List<CategoryResponse> getAllCategories();

    /**
     * Validates that all supplied category IDs exist and are active.
     *
     * <p>Checks all IDs against registered categories. Rejects the validation if any category ID
     * cannot be found or if any resolved category is currently inactive.</p>
     *
     * @param categoryIds the unique category identifiers to validate
     * @return {@code true} when all category IDs are found and currently active
     * @throws com.reallive.channelservice.common.exception.AppException with
     *         {@link com.reallive.channelservice.common.exception.ErrorCode#CATEGORY_NOT_FOUND}
     *         if one or more category IDs do not exist, or
     *         {@link com.reallive.channelservice.common.exception.ErrorCode#CATEGORY_NOT_ACTIVE}
     *         if any existing category is not active
     */
    boolean validateCategories(Set<UUID> categoryIds);
}
