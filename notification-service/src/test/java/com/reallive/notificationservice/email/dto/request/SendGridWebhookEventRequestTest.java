package com.reallive.notificationservice.email.dto.request;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SendGridWebhookEventRequestTest {

    private final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void readsEventArrayWithRootCorrelationAndOptionalDeliveryFields() {
        String json = """
                [
                  {
                    "email": "user@example.com",
                    "timestamp": 1791514805,
                    "event": "delivered",
                    "sg_event_id": "delivered-event-id",
                    "sg_message_id": "provider-message-id",
                    "eventId": "d17d3b74-4ec5-4e81-bfe3-a7adbf292311",
                    "response": "250 OK",
                    "smtp-id": "<smtp-message-id>",
                    "tls": 1
                  },
                  {
                    "email": "user@example.com",
                    "timestamp": 1791514806,
                    "event": "dropped",
                    "sg_event_id": "dropped-event-id",
                    "reason": "Bounced Address",
                    "status": "5.0.0"
                  },
                  {
                    "email": "user@example.com",
                    "timestamp": 1791514807,
                    "event": "bounce",
                    "sg_event_id": "bounce-event-id",
                    "reason": "Mailbox unavailable",
                    "type": "bounce"
                  }
                ]
                """;

        SendGridWebhookEventRequest[] events = objectMapper.readValue(
                json, SendGridWebhookEventRequest[].class);

        assertEquals(3, events.length);
        assertEquals("delivered", events[0].getEvent());
        assertEquals("delivered-event-id", events[0].getSgEventId());
        assertEquals("provider-message-id", events[0].getSgMessageId());
        assertEquals("d17d3b74-4ec5-4e81-bfe3-a7adbf292311", events[0].getEventId());
        assertEquals("250 OK", events[0].getResponse());
        assertNull(events[0].getReason());
        assertEquals("Bounced Address", events[1].getReason());
        assertEquals("5.0.0", events[1].getStatus());
        assertNull(events[1].getResponse());
        assertNull(events[1].getEventId());
        assertEquals("bounce", events[2].getEvent());
        assertNull(events[2].getSgMessageId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1791514805", "\"1791514805\""})
    void readsTimestampAsUnixSeconds(String timestampJson) {
        String json = "{\"timestamp\":" + timestampJson + ",\"event\":\"processed\"}";

        SendGridWebhookEventRequest event = objectMapper.readValue(
                json, SendGridWebhookEventRequest.class);

        assertEquals(1791514805L, event.getTimestamp());
    }
}
