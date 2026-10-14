package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.response.CategoryResponse;
import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.mapper.CategoryMapper;
import com.reallive.channelservice.channel.repository.CategoryRepository;
import com.reallive.channelservice.channel.service.impl.CategoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryMapper categoryMapper;
    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryMapper = Mappers.getMapper(CategoryMapper.class);
        categoryService = new CategoryServiceImpl(categoryRepository, categoryMapper);
    }

    @Test
    void getAllCategoriesReturnsEmptyListWhenNoCategoriesExist() {
        when(categoryRepository.findAllByStatus(CategoryStatus.ACTIVE)).thenReturn(Collections.emptyList());

        List<CategoryResponse> result = categoryService.getAllCategories();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(categoryRepository).findAllByStatus(CategoryStatus.ACTIVE);
    }

    @Test
    void getAllCategoriesReturnsMappedResponsesWhenCategoriesExist() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        CategoryEntity entity1 = CategoryEntity.builder()
                .id(id1)
                .name("League of Legends")
                .slug("league-of-legends")
                .thumbnailUrl("https://example.com/lol.png")
                .status(CategoryStatus.ACTIVE)
                .build();

        CategoryEntity entity2 = CategoryEntity.builder()
                .id(id2)
                .name("Valorant")
                .slug("valorant")
                .thumbnailUrl("https://example.com/val.png")
                .status(CategoryStatus.ACTIVE)
                .build();

        when(categoryRepository.findAllByStatus(CategoryStatus.ACTIVE)).thenReturn(List.of(entity1, entity2));

        List<CategoryResponse> result = categoryService.getAllCategories();

        assertNotNull(result);
        assertEquals(2, result.size());

        CategoryResponse resp1 = result.get(0);
        assertEquals(id1, resp1.getId());
        assertEquals("League of Legends", resp1.getName());
        assertEquals("league-of-legends", resp1.getSlug());
        assertEquals("https://example.com/lol.png", resp1.getThumbnailUrl());

        CategoryResponse resp2 = result.get(1);
        assertEquals(id2, resp2.getId());
        assertEquals("Valorant", resp2.getName());
        assertEquals("valorant", resp2.getSlug());
        assertEquals("https://example.com/val.png", resp2.getThumbnailUrl());

        verify(categoryRepository).findAllByStatus(CategoryStatus.ACTIVE);
    }

    @Test
    void validateCategoriesReturnsTrueWhenAllCategoriesExistAndActive() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        CategoryEntity entity1 = CategoryEntity.builder()
                .id(id1)
                .name("League of Legends")
                .slug("league-of-legends")
                .status(CategoryStatus.ACTIVE)
                .build();

        CategoryEntity entity2 = CategoryEntity.builder()
                .id(id2)
                .name("Valorant")
                .slug("valorant")
                .status(CategoryStatus.ACTIVE)
                .build();

        when(categoryRepository.findAllById(Set.of(id1, id2))).thenReturn(List.of(entity1, entity2));

        boolean result = categoryService.validateCategories(Set.of(id1, id2));

        assertTrue(result);
        verify(categoryRepository).findAllById(Set.of(id1, id2));
    }

    @Test
    void validateCategoriesThrowsCategoryNotFoundWhenSomeCategoriesDoNotExist() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        CategoryEntity entity1 = CategoryEntity.builder()
                .id(id1)
                .name("League of Legends")
                .slug("league-of-legends")
                .status(CategoryStatus.ACTIVE)
                .build();

        when(categoryRepository.findAllById(Set.of(id1, id2))).thenReturn(List.of(entity1));

        com.reallive.channelservice.common.exception.AppException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.reallive.channelservice.common.exception.AppException.class,
                        () -> categoryService.validateCategories(Set.of(id1, id2)));

        assertEquals(com.reallive.channelservice.common.exception.ErrorCode.CATEGORY_NOT_FOUND, exception.getErrorCode());
        verify(categoryRepository).findAllById(Set.of(id1, id2));
    }

    @Test
    void validateCategoriesThrowsCategoryNotActiveWhenAnyCategoryIsInactive() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        CategoryEntity entity1 = CategoryEntity.builder()
                .id(id1)
                .name("League of Legends")
                .slug("league-of-legends")
                .status(CategoryStatus.ACTIVE)
                .build();

        CategoryEntity entity2 = CategoryEntity.builder()
                .id(id2)
                .name("Just Chatting")
                .slug("just-chatting")
                .status(CategoryStatus.INACTIVE)
                .build();

        when(categoryRepository.findAllById(Set.of(id1, id2))).thenReturn(List.of(entity1, entity2));

        com.reallive.channelservice.common.exception.AppException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.reallive.channelservice.common.exception.AppException.class,
                        () -> categoryService.validateCategories(Set.of(id1, id2)));

        assertEquals(com.reallive.channelservice.common.exception.ErrorCode.CATEGORY_NOT_ACTIVE, exception.getErrorCode());
        verify(categoryRepository).findAllById(Set.of(id1, id2));
    }
}
