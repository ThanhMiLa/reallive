package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.dto.response.CategoryResponse;
import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.mapper.CategoryMapper;
import com.reallive.channelservice.channel.repository.CategoryRepository;
import com.reallive.channelservice.channel.service.CategoryService;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CategoryServiceImpl implements CategoryService {

    CategoryRepository categoryRepository;
    CategoryMapper categoryMapper;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        List<CategoryEntity> categories = categoryRepository.findAllByStatus(CategoryStatus.ACTIVE);
        return categoryMapper.toResponseList(categories);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean validateCategories(Set<UUID> categoryIds) {
        List<CategoryEntity> categories = categoryRepository.findAllById(categoryIds);
        if (categories.size() != categoryIds.size()) {
            throw new AppException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        if (categories.stream().anyMatch(category -> category.getStatus() != CategoryStatus.ACTIVE)) {
            throw new AppException(ErrorCode.CATEGORY_NOT_ACTIVE);
        }
        return true;
    }
}
