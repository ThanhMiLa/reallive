package com.reallive.paymentservice.deposit.service;

import com.reallive.paymentservice.deposit.dto.response.VnpayIpnResponse;
import org.springframework.util.MultiValueMap;

public interface VnpayIpnService {

    /**
     * Validates a VNPAY IPN callback and settles the referenced deposit order when the signature is valid.
     *
     * <p>Rejects malformed or duplicated query parameters and invalid HMAC signatures with
     * {@link VnpayIpnResponse#INVALID_CHECKSUM} without modifying persistence state.</p>
     *
     * @param query the raw query parameters received from VNPAY's IPN callback
     * @return the VNPAY IPN acknowledgement response ({@code 00} on settlement, {@code 01} if the order is
     *         not found, {@code 02} if already confirmed, {@code 04} on amount mismatch, {@code 97} on
     *         checksum failure, or {@code 99} if required status fields are missing)
     */
    VnpayIpnResponse handle(MultiValueMap<String, String> query);
}
