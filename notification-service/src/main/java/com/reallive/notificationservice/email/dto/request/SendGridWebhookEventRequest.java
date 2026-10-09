package com.reallive.notificationservice.email.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

/** One event in the webhook array; timestamp is Unix time in seconds. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SendGridWebhookEventRequest {
    String email;
    Long timestamp;
    String event;

    @JsonProperty("sg_event_id")
    String sgEventId;

    @JsonProperty("sg_message_id")
    String sgMessageId;

    String eventId;
    String response;
    String reason;
    String status;
}
