package com.reallive.paymentservice.deposit.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutIpAddressTest {

    @ParameterizedTest
    @CsvSource({
            "'192.0.2.10, 192.0.2.11', 127.0.0.1, 192.0.2.10",
            "'::1, 192.0.2.11', 192.0.2.15, 127.0.0.1",
            "'999.0.2.10, 192.0.2.11', 192.0.2.15, 192.0.2.15",
            "'hostname.invalid, 192.0.2.11', 192.0.2.15, 192.0.2.15",
            ", 0:0:0:0:0:0:0:1, 127.0.0.1",
            ", ::ffff:192.0.2.3, 192.0.2.3",
            ", 2001:db8::1, 127.0.0.1"
    })
    void resolvesOnlyIpv4WithoutDns(String forwardedFor, String remote, String expected) {
        assertThat(CheckoutIpAddress.resolve(forwardedFor, remote)).isEqualTo(expected);
    }
}
