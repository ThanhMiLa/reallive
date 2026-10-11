package com.reallive.userservice.client.wallet;

import feign.FeignException;
import feign.RetryableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.cloud.openfeign.client.config.payment-service.connectTimeout=300",
        "spring.cloud.openfeign.client.config.payment-service.readTimeout=300"
})
@ActiveProfiles("test")
class WalletClientIntegrationTest {

    private static final PaymentHttpTestServer PAYMENT = new PaymentHttpTestServer();
    @Autowired
    private WalletClient client;
    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void paymentUrl(DynamicPropertyRegistry registry) {
        registry.add("app.service.payment", PAYMENT::baseUrl);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:wallet_feign;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void reset() {
        PAYMENT.reset();
    }

    @AfterEach
    void releaseDelayedReply() {
        PAYMENT.releaseResponse();
    }

    @AfterAll
    static void closeServer() {
        PAYMENT.close();
    }

    @Test
    void feignPostsUserIdToConfiguredInternalUrlAndDecodesGenericEnvelopeWithoutToken() throws Exception {
        UUID userId = UUID.randomUUID();
        var response = client.createWallet(userId);
        var request = PAYMENT.takeRequest();

        assertEquals("POST", request.method());
        assertEquals("/payment/internal/api/wallets/create/" + userId, request.path());
        assertNull(request.header("Cookie"));
        assertNull(request.header("Authorization"));
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertNotNull(response.getBody().getResult().getId());
        assertEquals(userId, response.getBody().getResult().getUserId());
        assertEquals(0L, response.getBody().getResult().getBalance());
        assertEquals(1, PAYMENT.callCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
    void httpErrorsBecomeFeignExceptionsWithoutAutomaticRetry(int status) {
        PAYMENT.setErrorStatus(status);
        FeignException failure = assertThrows(FeignException.class, () -> client.createWallet(UUID.randomUUID()));
        assertEquals(status, failure.status());
        assertEquals(1, PAYMENT.callCount());
    }

    @Test
    void malformedJsonCannotBeDecodedAsSuccessfulWallet() {
        PAYMENT.setMode(PaymentHttpTestServer.Mode.INVALID_JSON);
        assertThrows(FeignException.class, () -> client.createWallet(UUID.randomUUID()));
        assertEquals(1, PAYMENT.callCount());
    }

    @Test
    void emptyHttpSuccessCurrentlyProducesMissingResponseBody() {
        PAYMENT.setMode(PaymentHttpTestServer.Mode.EMPTY_BODY);
        assertNull(client.createWallet(UUID.randomUUID()).getBody());
        assertEquals(1, PAYMENT.callCount());
    }

    @Test
    void committedWalletWithLostResponseCausesReadTimeoutAndIsNotRetried() {
        UUID userId = UUID.randomUUID();
        PAYMENT.setMode(PaymentHttpTestServer.Mode.LOST_RESPONSE);

        assertThrows(RetryableException.class, () -> client.createWallet(userId));

        assertTrue(PAYMENT.hasWallet(userId));
        assertEquals(1, PAYMENT.callCount());
    }

    @Test
    void unavailablePaymentPortFailsConnectionWithoutReachingServer() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = unused.getLocalPort();
        }
        WalletClient unavailable = new FeignClientBuilder(context).forType(WalletClient.class, "payment-unavailable")
                .url("http://127.0.0.1:" + port + "/payment").build();

        assertThrows(RetryableException.class, () -> unavailable.createWallet(UUID.randomUUID()));
        assertEquals(0, PAYMENT.callCount());
    }
}
