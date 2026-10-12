package com.reallive.channelservice.common.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesSuccessEnvelopeWithStructuredResult() {
        UUID channelId = UUID.randomUUID();
        String timestamp = Instant.now().toString();
        ApiResponse<Map<String, String>> response = ApiResponse.<Map<String, String>>builder()
                .status(200).code(1000).message("Success")
                .result(Map.of("channelId", channelId.toString(), "slug", "test-channel"))
                .timestamp(timestamp).build();

        var json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertEquals(5, json.size());
        assertEquals(200, json.get("status").asInt());
        assertEquals(1000, json.get("code").asInt());
        assertEquals("Success", json.get("message").asText());
        assertEquals(channelId.toString(), json.get("result").get("channelId").asText());
        assertEquals("test-channel", json.get("result").get("slug").asText());
        assertEquals(timestamp, json.get("timestamp").asText());
    }

    @Test
    void serializesErrorEnvelopeWithExplicitNullResult() {
        ApiResponse<Object> response = ApiResponse.builder().status(401).code(1001)
                .message("Unauthorized").result(null).timestamp(Instant.now().toString()).build();

        var json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertEquals(5, json.size());
        assertEquals(401, json.get("status").asInt());
        assertEquals(1001, json.get("code").asInt());
        assertEquals("Unauthorized", json.get("message").asText());
        assertTrue(json.get("result").isNull());
        Instant.parse(json.get("timestamp").asText());
    }
}
