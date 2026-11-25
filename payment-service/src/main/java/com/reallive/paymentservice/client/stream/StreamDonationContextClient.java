package com.reallive.paymentservice.client.stream;

import com.reallive.paymentservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "stream-service", contextId = "streamDonationContext", url = "${app.service.stream}")
public interface StreamDonationContextClient {

    @GetMapping(value = "/internal/api/streams/{sessionId}/donation-context", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<StreamDonationContextResponse>> getDonationContext(@PathVariable("sessionId") UUID sessionId);
}
