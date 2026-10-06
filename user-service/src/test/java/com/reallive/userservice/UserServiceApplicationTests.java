package com.reallive.userservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.reallive.userservice.auth.TestSigningKeys;

@SpringBootTest
@ActiveProfiles("test")
class UserServiceApplicationTests {

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.signer-key", TestSigningKeys::privateKey);
    }

    @Test
    void contextLoads() {
    }

}
