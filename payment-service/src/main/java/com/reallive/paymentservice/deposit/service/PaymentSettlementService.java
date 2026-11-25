package com.reallive.paymentservice.deposit.service;

import com.reallive.paymentservice.client.vnpay.VnpayPaymentResult;
import com.reallive.paymentservice.deposit.dto.response.VnpayIpnResponse;

public interface PaymentSettlementService {

    /**
     * Atomically settles a pending deposit order from a verified VNPAY IPN notification.
     *
     * <p>Locks the order row to prevent concurrent double-settlement, transitions pending orders to
     * {@code PAID}, {@code CANCELLED}, or {@code FAILED}, and on {@code PAID} records a deposit wallet
     * transaction and credits the user's wallet balance within the same transaction. Already-settled orders
     * are not modified and return {@link VnpayIpnResponse#ALREADY_CONFIRMED}.</p>
     *
     * @param orderCode the merchant deposit order code ({@code vnp_TxnRef})
     * @param amount the payment amount reported by VNPAY in minor units ({@code vnp_Amount}, VND * 100)
     * @param result the verified provider status and transaction reference
     * @return {@link VnpayIpnResponse#CONFIRMED} when settled, {@link VnpayIpnResponse#ORDER_NOT_FOUND}
     *         if absent, {@link VnpayIpnResponse#INVALID_AMOUNT} on amount mismatch, or
     *         {@link VnpayIpnResponse#ALREADY_CONFIRMED} if no longer pending
     * @throws IllegalStateException if crediting a {@code PAID} deposit does not update exactly one wallet
     */
    VnpayIpnResponse settleFromIpn(long orderCode, long amount, VnpayPaymentResult result);

    /**
     * Atomically reconciles a pending deposit order using a verified VNPAY QueryDR result.
     *
     * <p>Locks the order row and ignores absent or already-settled orders. Transitions the order to
     * {@code PAID} (recording a wallet transaction and crediting coins) or {@code FAILED} when QueryDR
     * reports a terminal outcome, or to {@code CANCELLED} when the order is not found or still pending
     * after the expiration buffer has elapsed.</p>
     *
     * @param orderCode the merchant deposit order code ({@code vnp_TxnRef})
     * @param result the verified QueryDR status and transaction reference
     * @throws IllegalStateException if crediting a {@code PAID} deposit does not update exactly one wallet
     */
    void settleFromQueryDr(long orderCode, VnpayPaymentResult result);
}
