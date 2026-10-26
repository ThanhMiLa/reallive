package com.reallive.streamservice;

import org.junit.jupiter.api.Test;
import com.reallive.streamservice.stream.MediaTestSupport;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class StreamServiceApplicationTests {
    @DynamicPropertySource
    static void media(DynamicPropertyRegistry registry) {
        MediaTestSupport.register(registry);
    }

    @Test
    void contextLoads() {
    }

}
