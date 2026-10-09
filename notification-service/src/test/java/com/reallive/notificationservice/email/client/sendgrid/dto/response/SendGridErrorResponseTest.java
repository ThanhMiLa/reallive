package com.reallive.notificationservice.email.client.sendgrid.dto.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SendGridErrorResponseTest {

    private final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void readsMultipleErrorsWithNullableFieldsAndAdditionalProviderMetadata() {
        String json = """
                {
                  "errors": [
                    {
                      "message": "invalid request",
                      "field": null,
                      "id": "provider-error-id",
                      "help": {"message": "Check request fields"}
                    },
                    {
                      "message": "Recipient email is required",
                      "field": "personalizations.0.to.0.email"
                    }
                  ],
                  "additional_metadata": "ignored"
                }
                """;

        SendGridErrorResponse response = objectMapper.readValue(json, SendGridErrorResponse.class);

        assertEquals(2, response.getErrors().size());
        assertEquals("invalid request", response.getErrors().getFirst().getMessage());
        assertNull(response.getErrors().getFirst().getField());
        assertEquals("personalizations.0.to.0.email", response.getErrors().get(1).getField());
    }
}
