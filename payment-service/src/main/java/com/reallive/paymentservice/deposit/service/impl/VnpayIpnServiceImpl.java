package com.reallive.paymentservice.deposit.service.impl;

import com.reallive.paymentservice.deposit.adapter.vnpay.VnpayPayAdapter;
import com.reallive.paymentservice.deposit.adapter.vnpay.VnpayPaymentResult;
import com.reallive.paymentservice.deposit.dto.response.VnpayIpnResponse;
import com.reallive.paymentservice.deposit.service.PaymentSettlementService;
import com.reallive.paymentservice.deposit.service.VnpayIpnService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class VnpayIpnServiceImpl implements VnpayIpnService {

    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,19}");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z0-9_]+");

    VnpayPayAdapter payAdapter;
    PaymentSettlementService settlementService;

    @Override
    public VnpayIpnResponse handle(MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(entry ->
                !PARAMETER_NAME.matcher(entry.getKey()).matches() || entry.getValue().size() != 1)) {
            return VnpayIpnResponse.INVALID_CHECKSUM;
        }
        Map<String, String> parameters = query.toSingleValueMap();
        if (!payAdapter.verifiesIpn(parameters)) {
            return VnpayIpnResponse.INVALID_CHECKSUM;
        }

        long orderCode = parsePositiveLong(parameters.get("vnp_TxnRef"));
        if (orderCode <= 0) {
            return VnpayIpnResponse.ORDER_NOT_FOUND;
        }
        long amount = parsePositiveLong(parameters.get("vnp_Amount"));
        if (amount <= 0) {
            return VnpayIpnResponse.INVALID_AMOUNT;
        }
        String responseCode = parameters.get("vnp_ResponseCode");
        String transactionStatus = parameters.get("vnp_TransactionStatus");
        if (!StringUtils.hasText(responseCode) || !StringUtils.hasText(transactionStatus)) {
            return VnpayIpnResponse.UNKNOWN_ERROR;
        }
        return settlementService.settleFromIpn(orderCode, amount,
                new VnpayPaymentResult(responseCode, transactionStatus, parameters.get("vnp_TransactionNo")));
    }

    private static long parsePositiveLong(String value) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
