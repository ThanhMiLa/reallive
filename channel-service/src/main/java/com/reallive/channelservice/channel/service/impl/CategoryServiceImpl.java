package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.dto.response.CategoryResponse;
import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.mapper.CategoryMapper;
import com.reallive.channelservice.channel.repository.CategoryRepository;
import com.reallive.channelservice.channel.service.CategoryService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
}
