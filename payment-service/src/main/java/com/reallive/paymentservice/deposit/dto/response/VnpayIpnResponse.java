package com.reallive.paymentservice.deposit.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VnpayIpnResponse(
        @JsonProperty("RspCode") String rspCode,
        @JsonProperty("Message") String message
) {
    public static final VnpayIpnResponse CONFIRMED = new VnpayIpnResponse("00", "Confirm Success");
    public static final VnpayIpnResponse ORDER_NOT_FOUND = new VnpayIpnResponse("01", "Order not Found");
    public static final VnpayIpnResponse ALREADY_CONFIRMED = new VnpayIpnResponse("02", "Order already confirmed");
    public static final VnpayIpnResponse INVALID_AMOUNT = new VnpayIpnResponse("04", "Invalid Amount");
    public static final VnpayIpnResponse INVALID_CHECKSUM = new VnpayIpnResponse("97", "Invalid Checksum");
    public static final VnpayIpnResponse UNKNOWN_ERROR = new VnpayIpnResponse("99", "Unknown error");
}
