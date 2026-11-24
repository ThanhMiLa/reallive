package com.reallive.paymentservice.deposit.controller;

import com.reallive.paymentservice.deposit.dto.response.VnpayIpnResponse;
import com.reallive.paymentservice.deposit.service.VnpayIpnService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class VnpayIpnController {

    VnpayIpnService ipnService;

    @GetMapping("/api/webhooks/vnpay/ipn")
    public ResponseEntity<VnpayIpnResponse> ipn(@RequestParam MultiValueMap<String, String> query) {
        VnpayIpnResponse response;
        try {
            response = ipnService.handle(query);
        } catch (RuntimeException exception) {
            log.warn("VNPAY IPN settlement failed ({})", exception.getClass().getSimpleName());
            response = VnpayIpnResponse.UNKNOWN_ERROR;
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
