package com.reallive.notificationservice.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtSecurityErrorHandlersTest {

    @Test
    void authenticationFailureWritesCommitted401JsonWithoutExceptionDetails() throws Exception {
        var response = new MockHttpServletResponse();
        new JwtAuthenticationEntryPoint().commence(new MockHttpServletRequest(), response,
                new BadCredentialsException("private authentication detail"));
        assertEnvelope(response, 401, 1001, "Unauthorized");
        assertFalse(response.getContentAsString().contains("private authentication detail"));
    }

    @Test
    void permissionFailureWritesCommitted403JsonWithoutExceptionDetails() throws Exception {
        var response = new MockHttpServletResponse();
        new JwtAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("private authorization detail"));
        assertEnvelope(response, 403, 1002, "Access Denied");
        assertFalse(response.getContentAsString().contains("private authorization detail"));
    }

    private void assertEnvelope(MockHttpServletResponse response, int status, int code, String message) throws Exception {
        assertEquals(status, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
        assertTrue(response.isCommitted());
        var json = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals(5, json.size());
        assertEquals(status, json.get("status").asInt());
        assertEquals(code, json.get("code").asInt());
        assertEquals(message, json.get("message").asText());
        assertTrue(json.get("result").isNull());
        Instant.parse(json.get("timestamp").asText());
    }
}
