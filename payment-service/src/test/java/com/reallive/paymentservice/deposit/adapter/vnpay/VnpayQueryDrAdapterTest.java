package com.reallive.paymentservice.deposit.adapter.vnpay;

import com.reallive.paymentservice.deposit.configuration.VnpayProperties;
import com.reallive.paymentservice.deposit.entity.PaymentTransactionEntity;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.reallive.paymentservice.deposit.VnpayTestSignatures.MERCHANT;
import static com.reallive.paymentservice.deposit.VnpayTestSignatures.SECRET;
import static com.reallive.paymentservice.deposit.VnpayTestSignatures.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VnpayQueryDrAdapterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T18:00:00Z"), ZoneOffset.UTC);
    private static final String TRANSACTION_DATE = "20261124003530";
    private static final String API_PATH = "/merchant_webapi/api/transaction";
    private static final PaymentTransactionEntity ORDER = PaymentTransactionEntity.builder()
            .orderCode(1234567890L).amount(10_000).providerCreateDate(TRANSACTION_DATE).build();

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<Map<String, String>> response = new AtomicReference<>();
    private final LinkedBlockingQueue<RecordedRequest> requests = new LinkedBlockingQueue<>();

    private HttpServer server;
    private VnpayQueryDrAdapter adapter;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(API_PATH, exchange -> {
            try (exchange) {
                requests.add(new RecordedRequest(exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        exchange.getRequestBody().readAllBytes()));
                Map<String, String> reply = response.get();
                if (reply == null) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] body = mapper.writeValueAsBytes(reply);
                exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();

        VnpayProperties properties = new VnpayProperties(MERCHANT, SECRET, null,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + API_PATH),
                null, "2.1.0", "VND", "vn", "other", 15,
                new VnpayProperties.Reconcile(false, 300_000, 3, 10, 50, 5_000, "192.0.2.10"));
        adapter = new VnpayQueryDrAdapter(properties, new VnpayRequestSigner(properties), CLOCK, WebClient.builder());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsSignedQueryWithOriginalTransactionDateHcmRequestTimeAndDistinctRequestIds() {
        response.set(signedResponse(successResponse()));
        assertThat(adapter.query(ORDER)).contains(new VnpayPaymentResult("00", "00", "42"));
        assertThat(adapter.query(ORDER)).isPresent();

        RecordedRequest first = requests.remove();
        RecordedRequest second = requests.remove();
        assertThat(first.method()).isEqualTo("POST");
        assertThat(first.contentType()).startsWith("application/json");
        JsonNode query = mapper.readTree(first.body());
        String requestId = query.get("vnp_RequestId").asString();
        assertThat(requestId).matches("[a-f0-9]{32}")
                .isNotEqualTo(mapper.readTree(second.body()).get("vnp_RequestId").asString());
        assertThat(query.size()).isEqualTo(10);
        assertThat(query.get("vnp_TmnCode").asString()).isEqualTo(MERCHANT);
        assertThat(query.get("vnp_TxnRef").asString()).isEqualTo("1234567890");
        assertThat(query.get("vnp_TransactionDate").asString()).isEqualTo(TRANSACTION_DATE);
        assertThat(query.get("vnp_CreateDate").asString()).isEqualTo("20261124010000");
        assertThat(query.get("vnp_IpAddr").asString()).isEqualTo("192.0.2.10");
        assertThat(query.get("vnp_Command").asString()).isEqualTo("querydr");
        assertThat(query.get("vnp_Version").asString()).isEqualTo("2.1.0");
        assertThat(query.get("vnp_OrderInfo").asString()).isEqualTo("Truy van nap coin 1234567890");
        assertThat(query.get("vnp_SecureHash").asString()).isEqualTo(sign(
                requestId + "|2.1.0|querydr|" + MERCHANT + "|1234567890|" + TRANSACTION_DATE
                        + "|20261124010000|192.0.2.10|Truy van nap coin 1234567890"));
    }

    @ParameterizedTest
    @CsvSource({"vnp_Command,pay", "vnp_TmnCode,OTHER", "vnp_TxnRef,100", "vnp_Amount,999999"})
    void rejectsSignedResponsesForTheWrongCommandMerchantOrderOrAmount(String field, String value) {
        Map<String, String> fields = successResponse();
        fields.put(field, value);
        response.set(signedResponse(fields));
        assertThat(adapter.query(ORDER)).isEmpty();
    }

    @Test
    void rejectsBadSignatureAndTamperingOfSignedPromotionFields() {
        Map<String, String> fields = signedResponse(successResponse());
        fields.put("vnp_PromotionAmount", "20000");
        response.set(fields);
        assertThat(adapter.query(ORDER)).isEmpty();
        fields.put("vnp_SecureHash", "z".repeat(128));
        assertThat(adapter.query(ORDER)).isEmpty();
    }

    @Test
    void acceptsSignedResponseWithOmittedCommand() {
        Map<String, String> fields = successResponse();
        fields.remove("vnp_Command");
        response.set(signedResponse(fields));
        assertThat(adapter.query(ORDER)).contains(new VnpayPaymentResult("00", "00", "42"));
    }

    @Test
    void acceptsSignedNotFoundWithoutAmountButRequiresAmountForFoundOrder() {
        Map<String, String> fields = new HashMap<>(Map.of(
                "vnp_ResponseId", "44", "vnp_Command", "querydr", "vnp_TmnCode", MERCHANT,
                "vnp_TxnRef", "1234567890", "vnp_ResponseCode", "91", "vnp_Message", "Không tìm thấy"));
        response.set(signedResponse(fields));
        assertThat(adapter.query(ORDER)).contains(new VnpayPaymentResult("91", null, null));
        fields.put("vnp_ResponseCode", "00");
        fields.put("vnp_TransactionStatus", "00");
        response.set(signedResponse(fields));
        assertThat(adapter.query(ORDER)).isEmpty();
    }

    @Test
    void providerHttpFailureIsReportedToScheduler() {
        assertThatThrownBy(() -> adapter.query(ORDER)).isInstanceOf(WebClientResponseException.ServiceUnavailable.class);
    }

    private static Map<String, String> successResponse() {
        return new HashMap<>(Map.ofEntries(
                Map.entry("vnp_ResponseId", "44"), Map.entry("vnp_Command", "querydr"),
                Map.entry("vnp_ResponseCode", "00"), Map.entry("vnp_Message", "Thành công"),
                Map.entry("vnp_TmnCode", MERCHANT), Map.entry("vnp_TxnRef", "1234567890"),
                Map.entry("vnp_Amount", "1000000"), Map.entry("vnp_BankCode", "NCB"),
                Map.entry("vnp_PayDate", TRANSACTION_DATE), Map.entry("vnp_TransactionNo", "42"),
                Map.entry("vnp_TransactionType", "01"), Map.entry("vnp_TransactionStatus", "00"),
                Map.entry("vnp_OrderInfo", "Nap coin RealLive 1234567890"), Map.entry("vnp_PromotionCode", "1"),
                Map.entry("vnp_PromotionAmount", "0")));
    }

    private static Map<String, String> signedResponse(Map<String, String> fields) {
        String payload = Stream.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode",
                        "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo",
                        "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode", "vnp_PromotionAmount")
                .map(field -> fields.getOrDefault(field, ""))
                .collect(Collectors.joining("|"));
        Map<String, String> result = new HashMap<>(fields);
        result.put("vnp_SecureHash", sign(payload));
        return result;
    }

    private record RecordedRequest(
            String method,
            String contentType,
            byte[] body
    ) {}
}
