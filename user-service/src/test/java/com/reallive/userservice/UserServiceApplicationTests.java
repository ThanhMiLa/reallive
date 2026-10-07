package com.reallive.userservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@SpringBootTest
@ActiveProfiles("test")
class UserServiceApplicationTests {

    private static final String TEST_SIGNING_KEY = "1234567890123456789012345678901234567890123456789012345678901234";

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.signer-key", () -> TEST_SIGNING_KEY);
    }

    @Test
    void contextLoads() {
    }

}
