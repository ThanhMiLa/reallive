package com.reallive.streamservice.common.exception;

import com.reallive.streamservice.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void applicationErrorsUseTheStandardEnvelope() {
        var response = handler.appException(new AppException(ErrorCode.ACCESS_DENIED));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        ApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(403, body.getStatus());
        assertEquals(1002, body.getCode());
        assertEquals("Access Denied", body.getMessage());
        assertNull(body.getResult());
        assertDoesNotThrow(() -> Instant.parse(body.getTimestamp()));
    }

    @Test
    void invalidRequestsDoNotExposeParserDetails() {
        var exception = new HttpMessageNotReadableException("Internal parser details",
                new MockHttpInputMessage(new byte[0]));

        var response = handler.invalidRequest(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1004, response.getBody().getCode());
        assertEquals("Invalid stream request", response.getBody().getMessage());
    }

    @Test
    void databaseFailuresDoNotExposeConnectionDetails() {
        var response = handler.dataAccessException(new DataAccessResourceFailureException("Internal database details"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1003, response.getBody().getCode());
        assertEquals("Stream data unavailable", response.getBody().getMessage());
        assertNull(response.getBody().getResult());
    }
}
