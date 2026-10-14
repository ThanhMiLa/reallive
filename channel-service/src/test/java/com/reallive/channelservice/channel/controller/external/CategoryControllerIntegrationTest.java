package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "server.servlet.context-path=/channel",
        "spring.datasource.url=jdbc:h2:mem:category_controller_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();
    }

    @Test
    void getCategoriesReturnsEmptyListWhenNoCategoriesExist() throws Exception {
        mvc.perform(get("/channel/api/categories").contextPath("/channel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Categories retrieved successfully"))
                .andExpect(jsonPath("$.result", hasSize(0)))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void getCategoriesReturnsOnlyActiveCategoriesUnauthenticated() throws Exception {
        CategoryEntity category1 = categoryRepository.save(CategoryEntity.builder()
                .name("League of Legends")
                .slug("league-of-legends")
                .thumbnailUrl("https://example.com/lol.png")
                .status(CategoryStatus.ACTIVE)
                .build());

        categoryRepository.save(CategoryEntity.builder()
                .name("Just Chatting")
                .slug("just-chatting")
                .thumbnailUrl("https://example.com/chat.png")
                .status(CategoryStatus.INACTIVE)
                .build());

        mvc.perform(get("/channel/api/categories").contextPath("/channel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Categories retrieved successfully"))
                .andExpect(jsonPath("$.result", hasSize(1)))
                .andExpect(jsonPath("$.result[0].id").value(category1.getId().toString()))
                .andExpect(jsonPath("$.result[0].name").value("League of Legends"))
                .andExpect(jsonPath("$.result[0].slug").value("league-of-legends"))
                .andExpect(jsonPath("$.result[0].thumbnailUrl").value("https://example.com/lol.png"))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }
}
