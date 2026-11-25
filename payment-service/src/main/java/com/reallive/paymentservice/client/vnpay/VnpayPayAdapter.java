package com.reallive.paymentservice.client.vnpay;

import com.reallive.paymentservice.deposit.configuration.VnpayProperties;
import com.reallive.paymentservice.deposit.entity.PaymentTransactionEntity;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class VnpayPayAdapter {

    VnpayProperties properties;
    VnpayRequestSigner signer;

    public String paymentUrl(PaymentTransactionEntity payment, Instant expiresAt, String ipAddress) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("vnp_Version", properties.version());
        parameters.put("vnp_Command", "pay");
        parameters.put("vnp_TmnCode", properties.tmnCode());
        parameters.put("vnp_Amount", Long.toString(payment.getAmount() * 100L));
        parameters.put("vnp_CurrCode", properties.currCode());
        parameters.put("vnp_TxnRef", Long.toString(payment.getOrderCode()));
        parameters.put("vnp_OrderInfo", "Nap coin RealLive " + payment.getOrderCode());
        parameters.put("vnp_OrderType", properties.orderType());
        parameters.put("vnp_Locale", properties.locale());
        parameters.put("vnp_ReturnUrl", properties.returnUrl());
        parameters.put("vnp_IpAddr", ipAddress);
        parameters.put("vnp_CreateDate", payment.getProviderCreateDate());
        parameters.put("vnp_ExpireDate", VnpayRequestSigner.formatDate(expiresAt));
        String query = VnpayRequestSigner.canonicalQuery(parameters);
        return properties.payUrl().toASCIIString() + "?" + query + "&vnp_SecureHash=" + signer.sign(query);
    }

    public boolean verifiesIpn(Map<String, String> parameters) {
        return signer.hasCredentials() && properties.tmnCode().equals(parameters.get("vnp_TmnCode"))
                && signer.verifies(VnpayRequestSigner.canonicalQuery(parameters), parameters.get("vnp_SecureHash"));
    }
}
