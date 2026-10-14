package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.response.CategoryResponse;

import java.util.List;

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
}
