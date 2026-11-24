package com.reallive.paymentservice.deposit.adapter.vnpay;

import com.reallive.paymentservice.deposit.configuration.VnpayProperties;
import com.reallive.paymentservice.deposit.entity.PaymentTransactionEntity;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class VnpayQueryDrAdapter {

    private static final String COMMAND = "querydr";
    private static final String[] REQUEST_FIELDS = {
            "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TxnRef",
            "vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo"
    };
    private static final String[] RESPONSE_FIELDS = {
            "vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode",
            "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo",
            "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo",
            "vnp_PromotionCode", "vnp_PromotionAmount"
    };
    private static final ParameterizedTypeReference<Map<String, String>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    VnpayProperties properties;
    VnpayRequestSigner signer;
    Clock paymentClock;
    WebClient webClient;

    public Optional<VnpayPaymentResult> query(PaymentTransactionEntity payment) {
        String orderCode = Long.toString(payment.getOrderCode());
        Map<String, String> request = new HashMap<>();
        request.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
        request.put("vnp_Version", properties.version());
        request.put("vnp_Command", COMMAND);
        request.put("vnp_TmnCode", properties.tmnCode());
        request.put("vnp_TxnRef", orderCode);
        request.put("vnp_TransactionDate", payment.getProviderCreateDate());
        request.put("vnp_CreateDate", VnpayRequestSigner.formatDate(paymentClock.instant()));
        request.put("vnp_IpAddr", properties.reconcile().ipAddress());
        request.put("vnp_OrderInfo", "Truy van nap coin " + orderCode);
        request.put("vnp_SecureHash", signer.sign(pipeDelimited(request, REQUEST_FIELDS)));

        Map<String, String> response = webClient.post()
                .uri(properties.apiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RESPONSE_TYPE)
                .block(Duration.ofMillis(properties.reconcile().httpTimeoutMs()));

        if (response == null || !signer.verifies(pipeDelimited(response, RESPONSE_FIELDS), response.get("vnp_SecureHash"))
                || !matchesCommand(response.get("vnp_Command"))
                || !properties.tmnCode().equals(response.get("vnp_TmnCode"))
                || !orderCode.equals(response.get("vnp_TxnRef"))
                || !matchesAmount(payment, response)) {
            return Optional.empty();
        }
        return Optional.of(new VnpayPaymentResult(response.get("vnp_ResponseCode"),
                response.get("vnp_TransactionStatus"), response.get("vnp_TransactionNo")));
    }

    private static boolean matchesCommand(String command) {
        return !StringUtils.hasText(command) || COMMAND.equals(command);
    }

    private static boolean matchesAmount(PaymentTransactionEntity payment, Map<String, String> response) {
        String amount = response.get("vnp_Amount");
        // A signed not-found response can omit the amount because VNPAY has no payment record.
        if ((amount == null || amount.isEmpty()) && "91".equals(response.get("vnp_ResponseCode"))) {
            return true;
        }
        return Long.toString(payment.getAmount() * 100L).equals(amount);
    }

    private static String pipeDelimited(Map<String, String> parameters, String[] fields) {
        return Arrays.stream(fields)
                .map(field -> parameters.get(field) == null ? "" : parameters.get(field))
                .collect(Collectors.joining("|"));
    }
}
