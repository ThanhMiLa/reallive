package com.reallive.notificationservice.common.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesSuccessEnvelopeWithNestedResult() {
        var response = ApiResponse.builder().status(200).code(1000).message("Success")
                .result(Map.of("emailVerified", true)).timestamp("2026-10-10T00:00:00Z").build();
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals(5, json.size());
        assertEquals(200, json.get("status").asInt());
        assertEquals(1000, json.get("code").asInt());
        assertEquals("Success", json.get("message").asText());
        assertTrue(json.get("result").get("emailVerified").asBoolean());
        assertEquals("2026-10-10T00:00:00Z", json.get("timestamp").asText());
    }

    @Test
    void serializesErrorEnvelopeWithNullResult() {
        var response = ApiResponse.builder().status(401).code(1001).message("Unauthorized")
                .timestamp("2026-10-10T00:00:00Z").build();
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals(5, json.size());
        assertEquals(401, json.get("status").asInt());
        assertEquals(1001, json.get("code").asInt());
        assertTrue(json.get("result").isNull());
    }
}
