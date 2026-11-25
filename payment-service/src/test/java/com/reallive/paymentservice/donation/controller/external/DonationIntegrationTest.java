package com.reallive.paymentservice.donation.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.paymentservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.paymentservice.client.stream.StreamDonationContextApiClient;
import com.reallive.paymentservice.donation.dto.request.DonateGiftRequest;
import com.reallive.paymentservice.donation.repository.DonationRepository;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.repository.StreamerWalletRepository;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import com.reallive.paymentservice.wallet.repository.WalletTransactionRepository;
import com.reallive.paymentservice.wallet.entity.WalletTransactionEntity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.cloud.openfeign.client.config.streamDonationContext.connectTimeout=300",
        "spring.cloud.openfeign.client.config.streamDonationContext.readTimeout=300",
        "spring.cloud.openfeign.client.config.stream-service.connectTimeout=300",
        "spring.cloud.openfeign.client.config.stream-service.readTimeout=300"
})
@ActiveProfiles("test")
class DonationIntegrationTest {

    private static final String CONTEXT = "/payment";
    private static final String DONATE_PATH = CONTEXT + "/api/donations";
    private static final String SCHEMA = "donation_api_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID ROSE = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID HEART = UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final ExecutorService SERVER_EXECUTOR = Executors.newCachedThreadPool();
    private static final Queue<String> STREAM_REQUESTS = new ConcurrentLinkedQueue<>();
    private static volatile int upstreamStatus;
    private static volatile String upstreamBody;
    private static volatile boolean blockResponse;
    private static volatile CountDownLatch releaseResponse = new CountDownLatch(1);
    private static final HttpServer STREAM_SERVICE = startStreamService();

    @MockitoBean
    private RedisAuthoritiesReader authoritiesReader;
    @MockitoSpyBean
    private StreamDonationContextApiClient streamClient;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserWalletRepository wallets;
    @Autowired
    private StreamerWalletRepository streamerWallets;
    @Autowired
    private DonationRepository donations;
    @Autowired
    private WalletTransactionRepository transactions;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private UUID donor;
    private UUID stream;
    private UUID streamer;
    private UUID channel;
    private UUID owner;
    private Cookie cookie;
    private final AtomicBoolean lookupInTransaction = new AtomicBoolean();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("wallet.test.database-url",
                "jdbc:h2:mem:donation_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("wallet.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("wallet.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
        registry.add("app.service.stream", () -> "http://127.0.0.1:" + STREAM_SERVICE.getAddress().getPort() + "/stream");
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactions.deleteAll();
        donations.deleteAll();
        wallets.deleteAll();
        streamerWallets.deleteAll();
        jdbc.update("UPDATE \"" + SCHEMA + "\".virtual_gifts SET status = 'ACTIVE'");
        STREAM_REQUESTS.clear();
        donor = UUID.randomUUID();
        stream = UUID.randomUUID();
        streamer = UUID.randomUUID();
        channel = UUID.randomUUID();
        owner = UUID.randomUUID();
        upstreamStatus = 200;
        blockResponse = false;
        releaseResponse = new CountDownLatch(1);
        lookupInTransaction.set(false);
        replyContext("PUBLIC", "LIVE");
        when(authoritiesReader.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        cookie = accessCookie(donor);
        doAnswer(invocation -> {
            lookupInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(streamClient).getDonationContext(any());
    }

    @AfterEach
    void releaseResponses() {
        releaseResponse.countDown();
        assertFalse(lookupInTransaction.get(), "Stream HTTP lookup must not hold an active database transaction");
    }

    @AfterAll
    static void stop(@Autowired JdbcTemplate jdbc) {
        STREAM_SERVICE.stop(0);
        SERVER_EXECUTOR.shutdownNow();
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void publicCatalogReturnsEightSeededSortedGiftsWithAndWithoutCookies() throws Exception {
        var response = mvc.perform(get(CONTEXT + "/api/gifts").contextPath(CONTEXT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("Virtual gifts retrieved successfully"))
                .andExpect(jsonPath("$.result.length()").value(8)).andReturn();
        verifyNoInteractions(authoritiesReader);
        JsonNode catalog = result(response);
        String[] names = {"Rose", "Heart", "Fire", "Star", "Crown", "Diamond", "Rocket", "Galaxy"};
        long[] prices = {1, 5, 10, 25, 100, 250, 500, 1000};
        for (int index = 0; index < names.length; index++) {
            assertEquals(names[index], catalog.get(index).path("name").asString());
            assertEquals(prices[index], catalog.get(index).path("coinPrice").asLong());
            assertEquals("/gifts/" + names[index].toLowerCase(Locale.ROOT) + ".svg",
                    catalog.get(index).path("imageUrl").asString());
        }
        mvc.perform(get(CONTEXT + "/api/gifts").contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.length()").value(8));
        jdbc.update("UPDATE \"" + SCHEMA + "\".virtual_gifts SET status = 'INACTIVE' WHERE id = ?", ROSE);
        mvc.perform(get(CONTEXT + "/api/gifts").contextPath(CONTEXT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.length()").value(7))
                .andExpect(jsonPath("$.result[0].name").value("Heart"));
        mvc.perform(post(CONTEXT + "/api/gifts").contextPath(CONTEXT)).andExpect(status().isUnauthorized());
        mvc.perform(get(CONTEXT + "/api/gifts/extra").contextPath(CONTEXT)).andExpect(status().isUnauthorized());
        assertTrue(STREAM_REQUESTS.isEmpty());
    }

    @Test
    void completedDonationDebitsOnceAndCreditsFullPriceAndRecordsLedgerReference() throws Exception {
        createWallet(10);
        var request = request(ROSE);
        var response = donate(request, 200, 0);
        var donation = donations.findByDonorUserIdAndIdempotencyKey(donor, request.idempotencyKey()).orElseThrow();
        assertEquals(donation.getId().toString(), result(response).path("donationId").asString());
        assertEquals(stream.toString(), result(response).path("streamId").asString());
        assertEquals(channel.toString(), result(response).path("channelId").asString());
        assertEquals(streamer.toString(), result(response).path("streamerId").asString());
        assertEquals(ROSE.toString(), result(response).path("giftId").asString());
        assertEquals("Rose", result(response).path("giftName").asString());
        assertEquals("/gifts/rose.svg", result(response).path("giftImageUrl").asString());
        assertEquals(1, result(response).path("coinPrice").asLong());
        assertEquals(9, result(response).path("remainingBalance").asLong());
        assertFalse(result(response).path("createdAt").asString().isBlank());
        assertEquals(9L, wallets.findByUserId(donor).orElseThrow().getBalance());
        assertEquals(1L, streamerWallets.findByStreamerId(streamer).orElseThrow().getAvailableBalance());
        assertEquals(1, transactions.count());
        var transaction = transactions.findAll().getFirst();
        assertEquals(wallets.findByUserId(donor).orElseThrow().getId(), transaction.getWalletId());
        assertEquals(WalletTransactionEntity.Type.GIFT_PURCHASE, transaction.getType());
        assertEquals(donation.getId(), transaction.getReferenceId());
        assertEquals(1, transaction.getAmount());
        assertEquals(donation.getCreatedAt(), transaction.getCreatedAt());
        assertEquals(List.of("GET /stream/internal/api/streams/" + stream + "/donation-context"), List.copyOf(STREAM_REQUESTS));
    }

    @Test
    void anonymousPostFailsWithoutRemoteOrDatabaseChanges() throws Exception {
        var before = databaseState();
        mvc.perform(post(DONATE_PATH).contextPath(CONTEXT).contentType("application/json")
                        .content(JsonMapper.shared().writeValueAsString(request(ROSE))))
                .andExpect(status().isUnauthorized());
        assertEquals(before, databaseState());
        assertTrue(STREAM_REQUESTS.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"streamId\":\"invalid\"}", "null", "not-json",
            "{\"streamId\":null,\"giftId\":null,\"idempotencyKey\":null}"})
    void invalidBodyReturns400WithoutRemoteOrDatabaseChanges(String body) throws Exception {
        var before = databaseState();
        mvc.perform(post(DONATE_PATH).contextPath(CONTEXT).cookie(cookie)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1009));
        assertEquals(before, databaseState());
        assertTrue(STREAM_REQUESTS.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"404,404,1014", "500,503,1017", "503,503,1017", "401,503,1017"})
    void streamHttpErrorsLeaveDatabaseUnchanged(int remoteStatus, int expectedStatus, int expectedCode) throws Exception {
        createWallet(10);
        upstreamStatus = remoteStatus;
        upstreamBody = "{}";
        assertFailure(request(ROSE), expectedStatus, expectedCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"status\":200,\"code\":1000,\"result\":{}}",
            "{\"status\":200,\"code\":1000,\"result\":{\"sessionId\":\"invalid\"}}"})
    void malformedStreamResponsesLeaveDatabaseUnchanged(String body) throws Exception {
        createWallet(10);
        upstreamBody = body;
        assertFailure(request(ROSE), 503, 1017);
    }

    @Test
    void streamTimeoutLeavesDatabaseUnchanged() throws Exception {
        createWallet(10);
        blockResponse = true;
        assertFailure(request(ROSE), 503, 1017);
    }

    @ParameterizedTest
    @CsvSource({"PUBLIC,RECONNECTING,409,1015", "PUBLIC,ENDED,409,1015",
            "PRIVATE,LIVE,403,1016", "UNLISTED,LIVE,403,1016"})
    void rejectsIneligibleStreamWithoutMutations(String visibility, String state, int status, int code) throws Exception {
        createWallet(10);
        replyContext(visibility, state);
        assertFailure(request(ROSE), status, code);
    }

    @Test
    void rejectsSelfDonationWithoutMutations() throws Exception {
        createWallet(10);
        owner = donor;
        replyContext("PUBLIC", "LIVE");
        assertFailure(request(ROSE), 400, 1018);
    }

    @Test
    void rejectsMissingAndInactiveGiftsWithoutMutations() throws Exception {
        createWallet(10);
        assertFailure(request(UUID.randomUUID()), 404, 1019);
        jdbc.update("UPDATE \"" + SCHEMA + "\".virtual_gifts SET status = 'INACTIVE' WHERE id = ?", ROSE);
        assertFailure(request(ROSE), 409, 1020);
    }

    @Test
    void missingWalletAndInsufficientFundsRollBackDonationInsert() throws Exception {
        assertFailure(request(ROSE), 404, 1006);
        createWallet(0);
        assertFailure(request(ROSE), 400, 1021);
        createBalance(4);
        assertFailure(request(HEART), 400, 1021);
    }

    @Test
    void replayReturnsSameDonationCurrentBalanceAndNoSecondRemoteCallOrDebit() throws Exception {
        createWallet(10);
        var request = request(ROSE);
        var first = result(donate(request, 200, 0));
        createBalance(20);
        upstreamStatus = 503;
        var replay = result(donate(request, 200, 0));
        assertEquals(first.path("donationId"), replay.path("donationId"));
        assertEquals(first.path("createdAt"), replay.path("createdAt"));
        assertEquals(20, replay.path("remainingBalance").asLong());
        assertEquals(1, STREAM_REQUESTS.size());
        assertEquals(1, donations.count());
        assertEquals(1, transactions.count());
        assertEquals(1L, streamerWallets.findByStreamerId(streamer).orElseThrow().getAvailableBalance());
        assertFailure(new DonateGiftRequest(stream, HEART, request.idempotencyKey()), 409, 1022);
        assertFailure(new DonateGiftRequest(UUID.randomUUID(), ROSE, request.idempotencyKey()), 409, 1022);
        assertEquals(1, STREAM_REQUESTS.size());
    }

    @Test
    void concurrentDuplicateKeysWithOneGiftBalanceBothReturnSameDonation() throws Exception {
        createWallet(1);
        var request = request(ROSE);
        var responses = concurrent(request, request);
        for (var response : responses) {
            assertEquals(200, response.getResponse().getStatus());
        }
        assertEquals(result(responses.getFirst()).path("donationId"), result(responses.getLast()).path("donationId"));
        assertOneTransfer();
    }

    @Test
    void concurrentDistinctKeysWithOneGiftBalanceAllowOnlyOneTransfer() throws Exception {
        createWallet(1);
        var responses = concurrent(request(ROSE), request(ROSE));
        assertEquals(List.of(200, 400), responses.stream().map(response -> response.getResponse().getStatus()).sorted().toList());
        var failure = responses.stream().filter(response -> response.getResponse().getStatus() == 400).findFirst().orElseThrow();
        assertEquals(1021, JsonMapper.shared().readTree(failure.getResponse().getContentAsString()).path("code").asInt());
        assertOneTransfer();
    }

    private List<MvcResult> concurrent(DonateGiftRequest first, DonateGiftRequest second) throws Exception {
        var ready = new CountDownLatch(2);
        doAnswer(invocation -> {
            lookupInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            var result = invocation.callRealMethod();
            ready.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return result;
        }).when(streamClient).getDonationContext(any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> perform(first));
            var secondResult = executor.submit(() -> perform(second));
            return List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
        }
    }

    private void assertOneTransfer() {
        assertEquals(0L, wallets.findByUserId(donor).orElseThrow().getBalance());
        assertEquals(1, donations.count());
        assertEquals(1, transactions.count());
        assertEquals(1, streamerWallets.count());
        assertEquals(1L, streamerWallets.findByStreamerId(streamer).orElseThrow().getAvailableBalance());
    }

    private void assertFailure(DonateGiftRequest request, int status, int code) throws Exception {
        var before = databaseState();
        donate(request, status, code);
        assertEquals(before, databaseState(), "Failed donations must leave every payment table unchanged");
    }

    private MvcResult donate(DonateGiftRequest request, int expectedStatus, int expectedCode) throws Exception {
        var response = perform(request);
        assertEquals(expectedStatus, response.getResponse().getStatus(), response.getResponse().getContentAsString());
        var body = JsonMapper.shared().readTree(response.getResponse().getContentAsString());
        assertEquals(expectedStatus, body.path("status").asInt());
        assertEquals(expectedCode, body.path("code").asInt());
        return response;
    }

    private MvcResult perform(DonateGiftRequest request) throws Exception {
        return mvc.perform(post(DONATE_PATH).contextPath(CONTEXT).cookie(cookie)
                        .contentType("application/json").content(JsonMapper.shared().writeValueAsString(request)))
                .andReturn();
    }

    private JsonNode result(MvcResult response) throws Exception {
        return JsonMapper.shared().readTree(response.getResponse().getContentAsString()).path("result");
    }

    private DonateGiftRequest request(UUID giftId) {
        return new DonateGiftRequest(stream, giftId, UUID.randomUUID());
    }

    private void createWallet(long balance) {
        wallets.saveAndFlush(UserWalletEntity.builder().userId(donor).balance(balance).build());
    }

    private void createBalance(long balance) {
        var wallet = wallets.findByUserId(donor).orElseThrow();
        wallet.setBalance(balance);
        wallets.saveAndFlush(wallet);
    }

    private List<Object> databaseState() {
        return List.of(
                jdbc.queryForList("SELECT * FROM \"" + SCHEMA + "\".donations ORDER BY id"),
                jdbc.queryForList("SELECT * FROM \"" + SCHEMA + "\".wallet_transactions ORDER BY id"),
                jdbc.queryForList("SELECT * FROM \"" + SCHEMA + "\".user_wallets ORDER BY id"),
                jdbc.queryForList("SELECT * FROM \"" + SCHEMA + "\".streamer_wallets ORDER BY id"),
                jdbc.queryForList("SELECT * FROM \"" + SCHEMA + "\".virtual_gifts ORDER BY id"));
    }

    private Cookie accessCookie(UUID userId) throws Exception {
        var now = Instant.now();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder()
                .subject(userId.toString()).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
        token.sign(new MACSigner("donation-integration-test-key-".repeat(3)));
        return new Cookie("access_token", token.serialize());
    }

    private void replyContext(String visibility, String status) {
        upstreamBody = "{\"status\":200,\"code\":1000,\"result\":{\"sessionId\":\"" + stream
                + "\",\"channelId\":\"" + channel + "\",\"streamerId\":\"" + streamer + "\",\"ownerUserId\":\"" + owner
                + "\",\"visibility\":\"" + visibility + "\",\"status\":\"" + status + "\"}}";
    }

    private static HttpServer startStreamService() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(SERVER_EXECUTOR);
            server.createContext("/stream/internal/api/streams/", DonationIntegrationTest::handleExchange);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start Stream Service stub", exception);
        }
    }

    private static void handleExchange(HttpExchange exchange) throws IOException {
        STREAM_REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        var body = upstreamBody.getBytes(StandardCharsets.UTF_8);
        int status = upstreamStatus;
        var release = releaseResponse;
        try (exchange) {
            if (blockResponse) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }
}
