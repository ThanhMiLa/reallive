package com.reallive.paymentservice.deposit;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.paymentservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.paymentservice.deposit.adapter.vnpay.VnpayPaymentResult;
import com.reallive.paymentservice.deposit.entity.TransactionStatus;
import com.reallive.paymentservice.deposit.repository.PaymentTransactionRepository;
import com.reallive.paymentservice.deposit.scheduler.VnpayReconciliationScheduler;
import com.reallive.paymentservice.deposit.service.DepositService;
import com.reallive.paymentservice.deposit.service.PaymentSettlementService;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.reallive.paymentservice.deposit.VnpayTestSignatures.MERCHANT;
import static com.reallive.paymentservice.deposit.VnpayTestSignatures.ipnQuery;
import static com.reallive.paymentservice.deposit.VnpayTestSignatures.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/payment")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DepositIntegrationTest {

    private static final String CONTEXT = "/payment";
    private static final String DEPOSITS = CONTEXT + "/api/deposits";
    private static final String WALLET = CONTEXT + "/api/wallets/me";
    private static final String IPN = CONTEXT + "/api/webhooks/vnpay/ipn";
    private static final DateTimeFormatter PROVIDER_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserWalletRepository walletRepository;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private PaymentSettlementService settlement;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock paymentClock;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private RedisAuthoritiesReader authoritiesReader;

    @LocalServerPort
    private int port;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("deposit.test.database-url",
                "jdbc:h2:mem:deposit_flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("deposit.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("deposit.test.database-password", ""));
    }

    @BeforeEach
    void allowUsers() {
        when(authoritiesReader.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void ipnIsPublicAndUserEndpointsRequireAnAccessCookie() throws Exception {
        mvc.perform(get(IPN).contextPath(CONTEXT))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.RspCode").value("97"))
                .andExpect(jsonPath("$.Message").value("Invalid Checksum"))
                .andExpect(jsonPath("$.status").doesNotExist());
        mvc.perform(post(IPN).contextPath(CONTEXT)).andExpect(status().isUnauthorized());
        for (String path : List.of(WALLET, DEPOSITS + "/packages", DEPOSITS + "/123")) {
            mvc.perform(get(path).contextPath(CONTEXT)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(DEPOSITS + "/vnpay").contextPath(CONTEXT)).andExpect(status().isUnauthorized());
        assertThat(context.getBeansOfType(VnpayReconciliationScheduler.class)).isEmpty();
    }

    @Test
    void walletEndpointUsesAuthenticatedOwnerAndReturns404ForMissingWallet() throws Exception {
        UserWalletEntity wallet = wallet(75);
        mvc.perform(get(WALLET).contextPath(CONTEXT).cookie(access(wallet.getUserId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.walletId").value(wallet.getId().toString()))
                .andExpect(jsonPath("$.result.userId").value(wallet.getUserId().toString()))
                .andExpect(jsonPath("$.result.balance").value(75));
        mvc.perform(get(WALLET).contextPath(CONTEXT).cookie(access(UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1006));
    }

    @Test
    void packageEndpointReturnsFixedCatalog() throws Exception {
        var response = mvc.perform(get(DEPOSITS + "/packages").contextPath(CONTEXT).cookie(access(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        JsonNode packages = mapper.readTree(response).get("result");
        long[] coinAmounts = {100, 500, 1_000, 2_000, 5_000};
        for (int index = 0; index < coinAmounts.length; index++) {
            long coins = coinAmounts[index];
            JsonNode coinPackage = packages.get(index);
            assertThat(coinPackage.get("packageCode").asString()).isEqualTo("COIN_" + coins);
            assertThat(coinPackage.get("displayName").asString()).isEqualTo(String.format(Locale.US, "%,d Coins", coins));
            assertThat(coinPackage.get("amountVnd").asLong()).isEqualTo(coins * 100);
            assertThat(coinPackage.get("coinAmount").asLong()).isEqualTo(coins);
        }
    }

    @Test
    void checkoutPersistsPendingSnapshotAndSignsExactlyTheFourteenPayParameters() throws Exception {
        UserWalletEntity wallet = wallet(75);
        var response = mvc.perform(post(DEPOSITS + "/vnpay").contextPath(CONTEXT).cookie(access(wallet.getUserId()))
                        .header("X-Forwarded-For", "::1, 192.0.2.20")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packageCode\":\"COIN_500\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.packageCode").value("COIN_500"))
                .andExpect(jsonPath("$.result.amountVnd").value(50_000))
                .andExpect(jsonPath("$.result.coinAmount").value(500))
                .andExpect(jsonPath("$.result.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        JsonNode result = mapper.readTree(response).get("result");
        long orderCode = result.get("orderCode").asLong();
        assertThat(orderCode).isBetween(1L, (1L << 53) - 1);
        UUID paymentId = UUID.fromString(result.get("paymentId").asString());
        Instant expiresAt = Instant.parse(result.get("expiresAt").asString());
        var payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getOrderCode()).isEqualTo(orderCode);
        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(payment.getAmount()).isEqualTo(50_000);
        assertThat(payment.getCoinAmount()).isEqualTo(500);
        assertThat(payment.getProviderCreateDate()).isEqualTo(PROVIDER_DATE.format(expiresAt.minusSeconds(900)));
        assertThat(payment.getExpiresAt().atZone(paymentClock.getZone()).toInstant()).isEqualTo(expiresAt);

        URI url = URI.create(result.get("paymentUrl").asString());
        assertThat(url.getHost()).isEqualTo("sandbox.vnpayment.vn");
        assertThat(url.getPath()).isEqualTo("/paymentv2/vpcpay.html");
        String[] signed = url.getRawQuery().split("&vnp_SecureHash=", 2);
        assertThat(signed[1]).matches("[a-f0-9]{128}").isEqualTo(sign(signed[0]));
        String[] pairs = signed[0].split("&");
        assertThat(pairs).hasSize(13).isSorted();
        Map<String, String> values = new HashMap<>();
        for (String pair : pairs) {
            String[] field = pair.split("=", 2);
            values.put(field[0], URLDecoder.decode(field[1], StandardCharsets.US_ASCII));
        }
        assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("vnp_Version", "2.1.0"), Map.entry("vnp_Command", "pay"), Map.entry("vnp_TmnCode", MERCHANT),
                Map.entry("vnp_Amount", "5000000"), Map.entry("vnp_CurrCode", "VND"),
                Map.entry("vnp_TxnRef", Long.toString(orderCode)), Map.entry("vnp_OrderInfo", "Nap coin RealLive " + orderCode),
                Map.entry("vnp_OrderType", "other"), Map.entry("vnp_Locale", "vn"),
                Map.entry("vnp_ReturnUrl", "http://localhost:5173/payment/vnpay-return"),
                Map.entry("vnp_IpAddr", "127.0.0.1"), Map.entry("vnp_CreateDate", payment.getProviderCreateDate()),
                Map.entry("vnp_ExpireDate", PROVIDER_DATE.format(expiresAt))));

        mvc.perform(get(DEPOSITS + "/" + orderCode).contextPath(CONTEXT).cookie(access(wallet.getUserId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("PENDING"))
                .andExpect(jsonPath("$.result.walletBalance").value(75))
                .andExpect(jsonPath("$.result.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.result.expiresAt").value(expiresAt.toString()));
        mvc.perform(get(DEPOSITS + "/" + orderCode).contextPath(CONTEXT).cookie(access(wallet(0).getUserId())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1008));
        mvc.perform(get(DEPOSITS + "/0").contextPath(CONTEXT).cookie(access(wallet.getUserId())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1008));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "null", "{\"packageCode\":\" \"}", "{\"packageCode\":{}}"})
    void malformedCreateRequestsReturnStandardInvalidRequest(String body) throws Exception {
        mvc.perform(post(DEPOSITS + "/vnpay").contextPath(CONTEXT).cookie(access(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1009))
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void badPathUnknownPackageAndMissingWalletUseTheirContractCodes() throws Exception {
        Cookie cookie = access(UUID.randomUUID());
        mvc.perform(get(DEPOSITS + "/not-numeric").contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1009));
        mvc.perform(post(DEPOSITS + "/vnpay").contextPath(CONTEXT).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packageCode\":\"COIN_1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1007));
        mvc.perform(post(DEPOSITS + "/vnpay").contextPath(CONTEXT).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packageCode\":\"COIN_100\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1006));
    }

    @Test
    void invalidIpnInputsLeaveOrderAndWalletUnchanged() throws Exception {
        UserWalletEntity wallet = wallet(75);
        var payment = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        Map<String, String> callback = callback(payment.orderCode(), "00", "00", "101");
        String validQuery = signedQuery(callback);
        assertThat(ipn(validQuery.replace("1000000", "1000001"))).isEqualTo("97");
        assertThat(ipn(validQuery + "&vnp_Amount=1000000")).isEqualTo("97");

        callback.put("vnp_TmnCode", "OTHER");
        assertThat(ipn(signedQuery(callback))).isEqualTo("97");
        callback.put("vnp_TmnCode", MERCHANT);
        callback.put("vnp_TxnRef", "9223372036854775808");
        assertThat(ipn(signedQuery(callback))).isEqualTo("01");
        callback.put("vnp_TxnRef", "09000000000000000");
        assertThat(ipn(signedQuery(callback))).isEqualTo("01");
        callback.put("vnp_TxnRef", Long.toString(payment.orderCode()));
        callback.put("vnp_Amount", "NaN");
        assertThat(ipn(signedQuery(callback))).isEqualTo("04");
        callback.put("vnp_Amount", "5000000");
        assertThat(ipn(signedQuery(callback))).isEqualTo("04");
        assertThat(paymentRepository.findById(payment.paymentId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
        assertThat(balance(wallet)).isEqualTo(75);
        assertThat(ledgerCount(payment.paymentId())).isZero();
    }

    @Test
    void simultaneousDuplicateIpnsCreditExactlyOnce() throws Exception {
        UserWalletEntity wallet = wallet(75);
        var payment = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        String callback = signedQuery(callback(payment.orderCode(), "00", "00", "102"));
        assertThat(concurrently(() -> ipn(callback), () -> ipn(callback))).containsExactlyInAnyOrder("00", "02");

        assertThat(balance(wallet)).isEqualTo(175);
        assertThat(ledgerCount(payment.paymentId())).isOne();
        var settled = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(settled.getProviderTransactionNo()).isEqualTo("102");
        assertThat(settled.getProviderTransactionStatus()).isEqualTo("00");
        assertThat(settled.getPaidAt()).isNotNull();
        mvc.perform(get(DEPOSITS + "/" + payment.orderCode()).contextPath(CONTEXT).cookie(access(wallet.getUserId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("PAID"))
                .andExpect(jsonPath("$.result.providerTransactionNo").value("102"))
                .andExpect(jsonPath("$.result.providerResponseCode").value("00"))
                .andExpect(jsonPath("$.result.walletBalance").value(175))
                .andExpect(jsonPath("$.result.paidAt").isString());
    }

    @Test
    void parallelDistinctDepositsToOneWalletDoNotLoseCredit() throws Exception {
        UserWalletEntity wallet = wallet(75);
        var first = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        var second = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_500", "192.0.2.1");
        var firstIpn = callback(first.orderCode(), "00", "00", "103");
        var secondIpn = callback(second.orderCode(), "00", "00", "104");
        secondIpn.put("vnp_Amount", "5000000");
        assertThat(concurrently(() -> ipn(signedQuery(firstIpn)), () -> ipn(signedQuery(secondIpn))))
                .containsExactly("00", "00");
        assertThat(balance(wallet)).isEqualTo(675);
        assertThat(ledgerCount(first.paymentId())).isOne();
        assertThat(ledgerCount(second.paymentId())).isOne();
    }

    @ParameterizedTest
    @CsvSource({"24,02,CANCELLED", "51,00,FAILED", "00,02,FAILED"})
    void unsuccessfulIpnFinalizesWithoutCredit(String responseCode, String transactionStatus, TransactionStatus expected)
            throws Exception {
        UserWalletEntity wallet = wallet(75);
        var payment = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        String callback = signedQuery(callback(payment.orderCode(), responseCode, transactionStatus, " 0 "));
        assertThat(ipn(callback)).isEqualTo("00");
        assertThat(ipn(callback)).isEqualTo("02");
        var settled = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(expected);
        assertThat(settled.getProviderTransactionNo()).isNull();
        assertThat(settled.getPaidAt()).isNull();
        assertThat(balance(wallet)).isEqualTo(75);
        assertThat(ledgerCount(payment.paymentId())).isZero();
    }

    @Test
    void duplicateProviderTransactionRollsBackAndReturnsRaw99() throws Exception {
        UserWalletEntity wallet = wallet(75);
        var first = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        var second = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        assertThat(ipn(signedQuery(callback(first.orderCode(), "00", "00", "105")))).isEqualTo("00");
        assertThat(ipn(signedQuery(callback(second.orderCode(), "00", "00", "105")))).isEqualTo("99");
        assertThat(ledgerCount(second.paymentId())).isZero();
        assertThat(balance(wallet)).isEqualTo(175);
        assertThat(paymentRepository.findById(second.paymentId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
        assertThat(ipn(signedQuery(callback(second.orderCode(), "00", "00", "106")))).isEqualTo("00");
        assertThat(balance(wallet)).isEqualTo(275);
    }

    @Test
    void balanceUpdateFailureRollsBackOrderAndLedger() throws Exception {
        UserWalletEntity wallet = wallet(Long.MAX_VALUE);
        var payment = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        assertThat(ipn(signedQuery(callback(payment.orderCode(), "00", "00", "107")))).isEqualTo("99");
        assertThat(ledgerCount(payment.paymentId())).isZero();
        assertThat(balance(wallet)).isEqualTo(Long.MAX_VALUE);
        assertThat(paymentRepository.findById(payment.paymentId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @ParameterizedTest
    @CsvSource({"91,", "00,01"})
    void queryDrOnlyCancelsNotFoundOrIncompleteAfterExpirationBuffer(String responseCode, String transactionStatus) {
        UserWalletEntity wallet = wallet(75);
        var payment = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        var result = new VnpayPaymentResult(responseCode, transactionStatus, "0");
        jdbc.update("UPDATE payment_transactions SET expires_at = ? WHERE id = ?",
                LocalDateTime.now(paymentClock).minusMinutes(9), payment.paymentId());
        settlement.settleFromQueryDr(payment.orderCode(), result);
        assertThat(paymentRepository.findById(payment.paymentId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
        jdbc.update("UPDATE payment_transactions SET expires_at = ? WHERE id = ?",
                LocalDateTime.now(paymentClock).minusMinutes(11), payment.paymentId());
        settlement.settleFromQueryDr(payment.orderCode(), result);
        assertThat(paymentRepository.findById(payment.paymentId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.CANCELLED);
        assertThat(balance(wallet)).isEqualTo(75);
        assertThat(ledgerCount(payment.paymentId())).isZero();
    }

    @Test
    void queryDrAndIpnRaceThroughSameLockAndSettleOnce() throws Exception {
        UserWalletEntity wallet = wallet(75);
        var payment = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        String callback = signedQuery(callback(payment.orderCode(), "00", "00", "108"));
        assertThat(concurrently(() -> ipn(callback), () -> {
            settlement.settleFromQueryDr(payment.orderCode(), new VnpayPaymentResult("00", "00", "108"));
            return "QueryDR";
        }).getFirst()).isIn("00", "02");
        assertThat(paymentRepository.findById(payment.paymentId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PAID);
        assertThat(balance(wallet)).isEqualTo(175);
        assertThat(ledgerCount(payment.paymentId())).isOne();
    }

    @Test
    void queryDrConfirmedFailureIsTerminalWithoutCredit() {
        UserWalletEntity wallet = wallet(75);
        var payment = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        settlement.settleFromQueryDr(payment.orderCode(), new VnpayPaymentResult("99", null, null));
        assertThat(paymentRepository.findById(payment.paymentId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
        settlement.settleFromQueryDr(payment.orderCode(), new VnpayPaymentResult("00", "02", " "));
        settlement.settleFromQueryDr(payment.orderCode(), new VnpayPaymentResult("00", "00", "109"));
        assertThat(paymentRepository.findById(payment.paymentId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.FAILED);
        assertThat(balance(wallet)).isEqualTo(75);
        assertThat(ledgerCount(payment.paymentId())).isZero();
    }

    @Test
    void reconciliationRepositoryUsesPendingStatusAgeAndStableBatchOrder() {
        UserWalletEntity wallet = wallet(0);
        var first = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        var second = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        var finalized = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        LocalDateTime cutoff = LocalDateTime.of(2000, 1, 1, 12, 0);
        jdbc.update("UPDATE payment_transactions SET created_at = ? WHERE id IN (?, ?, ?)",
                cutoff, first.paymentId(), second.paymentId(), finalized.paymentId());
        settlement.settleFromQueryDr(finalized.orderCode(), new VnpayPaymentResult("00", "02", "0"));
        depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");

        long earlierCode = Math.min(first.orderCode(), second.orderCode());
        long laterCode = Math.max(first.orderCode(), second.orderCode());
        var firstBatch = paymentRepository.findForReconciliation(TransactionStatus.PENDING, cutoff,
                cutoff.minusSeconds(1), 0, PageRequest.ofSize(1));
        var remaining = paymentRepository.findForReconciliation(TransactionStatus.PENDING, cutoff,
                cutoff, earlierCode, PageRequest.ofSize(2));
        assertThat(firstBatch).extracting(payment -> payment.getOrderCode()).containsExactly(earlierCode);
        assertThat(remaining).extracting(payment -> payment.getOrderCode()).containsExactly(laterCode);
    }

    @Test
    void flywayEnforcesLedgerReferenceAndPaymentConstraints() throws Exception {
        UserWalletEntity wallet = wallet(0);
        var first = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");
        var second = depositService.createVnpayDeposit(wallet.getUserId(), "COIN_100", "192.0.2.1");

        for (String assignment : List.of("status = 'PAID'", "status = 'UNKNOWN'", "provider = 'OTHER'",
                "amount = 0", "coin_amount = -1", "provider_transaction_no = '0'", "provider_transaction_no = '  '")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE payment_transactions SET " + assignment + " WHERE id = ?",
                    first.paymentId())).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transactions SET order_code = ? WHERE id = ?",
                second.orderCode(), first.paymentId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedger(wallet.getId(), "DEPOSIT", 100, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedger(wallet.getId(), "DEPOSIT", 0, first.paymentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedger(wallet.getId(), "OTHER", 100, first.paymentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedger(UUID.randomUUID(), "DEPOSIT", 100, first.paymentId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(ipn(signedQuery(callback(first.orderCode(), "00", "00", "0")))).isEqualTo("00");
        assertThat(ipn(signedQuery(callback(second.orderCode(), "00", "00", "0")))).isEqualTo("00");
        assertThatThrownBy(() -> insertLedger(wallet.getId(), "DEPOSIT", 100, first.paymentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertLedger(wallet.getId(), "REFUND", 100, first.paymentId());
        insertLedger(wallet.getId(), "REFUND", 100, null);
        insertLedger(wallet.getId(), "REFUND", 100, null);
    }

    private UserWalletEntity wallet(long balance) {
        return walletRepository.saveAndFlush(UserWalletEntity.builder()
                .userId(UUID.randomUUID()).balance(balance).build());
    }

    private long balance(UserWalletEntity wallet) {
        return walletRepository.findById(wallet.getId()).orElseThrow().getBalance();
    }

    private long ledgerCount(UUID paymentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE reference_id = ?", Long.class, paymentId);
    }

    private void insertLedger(UUID walletId, String type, long amount, UUID referenceId) {
        jdbc.update("""
                INSERT INTO wallet_transactions (id, wallet_id, type, amount, reference_id, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), walletId, type, amount, referenceId);
    }

    private static Cookie access(UUID userId) throws Exception {
        Instant now = Instant.now();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512),
                new JWTClaimsSet.Builder().subject(userId.toString()).issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300))).build());
        token.sign(new MACSigner("payment-integration-test-secret-".repeat(4)));
        return new Cookie("access_token", token.serialize());
    }

    private static Map<String, String> callback(long orderCode, String responseCode, String status, String transactionNo) {
        return new HashMap<>(Map.of(
                "vnp_TmnCode", MERCHANT,
                "vnp_TxnRef", Long.toString(orderCode),
                "vnp_Amount", "1000000",
                "vnp_OrderInfo", "Nap coin + RealLive: " + orderCode,
                "vnp_ResponseCode", responseCode,
                "vnp_TransactionStatus", status,
                "vnp_TransactionNo", transactionNo));
    }

    private static String signedQuery(Map<String, String> callback) {
        String query = ipnQuery(callback);
        return query + "&vnp_BankCode=&vnp_SecureHashType=HMACSHA512&vnp_SecureHash=" + sign(query).toUpperCase(Locale.ROOT);
    }

    private String ipn(String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + IPN + "?" + query))
                .timeout(Duration.ofSeconds(10)).build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode acknowledgement = mapper.readTree(response.body());
        assertThat(acknowledgement.size()).isEqualTo(2);
        assertThat(acknowledgement.get("Message").asString()).isNotBlank();
        return acknowledgement.get("RspCode").asString();
    }

    private static List<String> concurrently(Callable<String> first, Callable<String> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> afterStart(first, ready, start));
            var secondResult = executor.submit(() -> afterStart(second, ready, start));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                return List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
        }
    }

    private static String afterStart(Callable<String> action, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for the concurrent settlement test");
        }
        return action.call();
    }
}
