package com.reallive.paymentservice.deposit.util;

import java.util.regex.Pattern;

public final class CheckoutIpAddress {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");

    private CheckoutIpAddress() {
    }

    public static String resolve(String forwardedFor, String remoteAddress) {
        if (forwardedFor != null) {
            String firstHop = ipv4(forwardedFor.split(",", 2)[0].strip());
            if (firstHop != null) {
                return firstHop;
            }
        }
        String remoteIp = ipv4(remoteAddress);
        return remoteIp == null ? LOOPBACK : remoteIp;
    }

    private static String ipv4(String address) {
        if (address == null) {
            return null;
        }
        if ("::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address)) {
            return LOOPBACK;
        }
        String candidate = address.regionMatches(true, 0, "::ffff:", 0, 7) ? address.substring(7) : address;
        return IPV4.matcher(candidate).matches() ? candidate : null;
    }
}
