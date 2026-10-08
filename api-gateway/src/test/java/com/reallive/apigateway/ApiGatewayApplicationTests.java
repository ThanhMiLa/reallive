package com.reallive.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "gateway.security.signer-key=test-signing-key-test-signing-key-test-signing-key-test-signing-key-")
@ActiveProfiles("dev")
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}
