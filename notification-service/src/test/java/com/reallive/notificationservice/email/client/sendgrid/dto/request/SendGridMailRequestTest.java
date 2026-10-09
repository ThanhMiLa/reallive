package com.reallive.notificationservice.email.client.sendgrid.dto.request;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SendGridMailRequestTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void serializesDynamicTemplatePayloadWithProviderFieldNames() {
        String expected = """
                {
                  "from": {"email": "no-reply@example.com", "name": "RealLive"},
                  "template_id": "d-12345678901234567890123456789012",
                  "personalizations": [{
                    "to": [{"email": "user@example.com"}],
                    "dynamic_template_data": {"otp": "012345", "expiresInMinutes": 5},
                    "custom_args": {"eventId": "d17d3b74-4ec5-4e81-bfe3-a7adbf292311"}
                  }]
                }
                """;

        String json = objectMapper.writeValueAsString(request());

        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(json));
    }

    @Test
    void omitsOptionalSenderNameWhenAbsent() {
        SendGridMailRequest request = request();
        request.getFrom().setName(null);

        String json = objectMapper.writeValueAsString(request);

        assertFalse(objectMapper.readTree(json).get("from").has("name"));
    }

    @Test
    void excludesOtpFromNestedAndParentToString() {
        SendGridMailRequest request = request();

        assertFalse(request.toString().contains("otp="));
        assertFalse(request.getPersonalizations().getFirst()
                .getDynamicTemplateData().toString().contains("otp="));
    }

    private SendGridMailRequest request() {
        return SendGridMailRequest.builder()
                .from(SendGridMailRequest.EmailAddress.builder()
                        .email("no-reply@example.com")
                        .name("RealLive")
                        .build())
                .templateId("d-12345678901234567890123456789012")
                .personalizations(List.of(SendGridMailRequest.Personalization.builder()
                        .to(List.of(SendGridMailRequest.EmailAddress.builder()
                                .email("user@example.com")
                                .build()))
                        .dynamicTemplateData(SendGridMailRequest.OtpTemplateData.builder()
                                .otp("012345")
                                .expiresInMinutes(5)
                                .build())
                        .customArgs(Map.of("eventId", "d17d3b74-4ec5-4e81-bfe3-a7adbf292311"))
                        .build()))
                .build();
    }
}
