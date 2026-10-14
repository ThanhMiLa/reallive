package com.reallive.channelservice.channel.controller.internal;

import com.reallive.channelservice.channel.dto.request.ValidateCategoriesRequest;
import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "server.servlet.context-path=/channel",
        "spring.datasource.url=jdbc:h2:mem:internal_category_controller_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalCategoryControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();
    }

    @Test
    void validateCategoriesReturns200AndTrueWhenAllCategoriesExistAndActive() throws Exception {
        CategoryEntity cat1 = categoryRepository.save(CategoryEntity.builder()
                .name("League of Legends")
                .slug("league-of-legends")
                .thumbnailUrl("https://example.com/lol.png")
                .status(CategoryStatus.ACTIVE)
                .build());

        CategoryEntity cat2 = categoryRepository.save(CategoryEntity.builder()
                .name("Valorant")
                .slug("valorant")
                .thumbnailUrl("https://example.com/val.png")
                .status(CategoryStatus.ACTIVE)
                .build());

        ValidateCategoriesRequest request = ValidateCategoriesRequest.builder()
                .categoryIds(Set.of(cat1.getId(), cat2.getId()))
                .build();

        mvc.perform(post("/channel/internal/api/categories/validate").contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Categories are valid and active"))
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void validateCategoriesReturns400AndCategoryNotFoundWhenAnyCategoryMissing() throws Exception {
        CategoryEntity cat1 = categoryRepository.save(CategoryEntity.builder()
                .name("League of Legends")
                .slug("league-of-legends")
                .status(CategoryStatus.ACTIVE)
                .build());

        ValidateCategoriesRequest request = ValidateCategoriesRequest.builder()
                .categoryIds(Set.of(cat1.getId(), UUID.randomUUID()))
                .build();

        mvc.perform(post("/channel/internal/api/categories/validate").contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1011))
                .andExpect(jsonPath("$.message").value("Category not found"));
    }

    @Test
    void validateCategoriesReturns400AndCategoryNotActiveWhenAnyCategoryInactive() throws Exception {
        CategoryEntity cat1 = categoryRepository.save(CategoryEntity.builder()
                .name("League of Legends")
                .slug("league-of-legends")
                .status(CategoryStatus.ACTIVE)
                .build());

        CategoryEntity cat2 = categoryRepository.save(CategoryEntity.builder()
                .name("Just Chatting")
                .slug("just-chatting")
                .status(CategoryStatus.INACTIVE)
                .build());

        ValidateCategoriesRequest request = ValidateCategoriesRequest.builder()
                .categoryIds(Set.of(cat1.getId(), cat2.getId()))
                .build();

        mvc.perform(post("/channel/internal/api/categories/validate").contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1012))
                .andExpect(jsonPath("$.message").value("Category is not active"));
    }

    @Test
    void validateCategoriesReturns400WhenCategoryIdsEmpty() throws Exception {
        ValidateCategoriesRequest request = ValidateCategoriesRequest.builder()
                .categoryIds(Collections.emptySet())
                .build();

        mvc.perform(post("/channel/internal/api/categories/validate").contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void validateCategoriesReturns400WhenPayloadMalformed() throws Exception {
        mvc.perform(post("/channel/internal/api/categories/validate").contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\": [\"invalid-uuid\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1004));
    }
}
