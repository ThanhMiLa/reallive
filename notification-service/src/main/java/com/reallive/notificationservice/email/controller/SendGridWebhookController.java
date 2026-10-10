package com.reallive.notificationservice.email.controller;

import com.reallive.notificationservice.common.dto.ApiResponse;
import com.reallive.notificationservice.email.service.SendGridWebhookService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SendGridWebhookController {

    SendGridWebhookService webhookService;

    @PostMapping("/api/webhooks/sendgrid")
    public ResponseEntity<ApiResponse<Void>> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Twilio-Email-Event-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Twilio-Email-Event-Webhook-Timestamp", required = false) String timestamp) {
        webhookService.receive(body, signature, timestamp);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .status(200)
                .code(1000)
                .message("Email delivery events received")
                .timestamp(Instant.now().toString())
                .build());
    }
}
