package com.reallive.paymentservice.deposit.adapter.vnpay;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.deposit.configuration.VnpayProperties;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class VnpayRequestSigner {

    private static final String ALGORITHM = "HmacSHA512";
    private static final DateTimeFormatter VNPAY_DATE = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss", Locale.ROOT)
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    VnpayProperties properties;

    boolean hasCredentials() {
        return StringUtils.hasText(properties.tmnCode()) && StringUtils.hasText(properties.hashSecret());
    }

    String sign(String payload) {
        if (!hasCredentials()) {
            throw new AppException(ErrorCode.PAYMENT_DATA_UNAVAILABLE);
        }
        return HexFormat.of().formatHex(mac(payload));
    }

    boolean verifies(String payload, String signature) {
        if (!hasCredentials() || signature == null || signature.length() != 128) {
            return false;
        }
        byte[] suppliedHash;
        try {
            suppliedHash = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(mac(payload), suppliedHash);
    }

    static String canonicalQuery(Map<String, String> parameters) {
        return new TreeMap<>(parameters).entrySet().stream()
                .filter(entry -> !"vnp_SecureHash".equals(entry.getKey())
                        && !"vnp_SecureHashType".equals(entry.getKey())
                        && StringUtils.hasText(entry.getValue()))
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
    }

    public static String formatDate(Instant instant) {
        return VNPAY_DATE.format(instant);
    }

    private byte[] mac(String payload) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.hashSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("VNPAY HMAC-SHA512 is unavailable", exception);
        }
    }
}
