package com.reallive.channelservice.common.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @ParameterizedTest
    @CsvSource({"UNAUTHENTICATED,401,1001,Unauthorized", "ACCESS_DENIED,403,1002,Access Denied",
            "CHANNEL_DATA_UNAVAILABLE,503,1003,Channel data unavailable"})
    void mapsAppExceptionToTheStandardHttpEnvelope(ErrorCode errorCode, int httpStatus, int code,
                                                   String message) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController(errorCode))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        Instant before = Instant.now();

        var response = mvc.perform(get("/test/failure"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.status").value(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andReturn().getResponse();

        var json = new ObjectMapper().readTree(response.getContentAsString());
        Instant timestamp = Instant.parse(json.get("timestamp").asText());
        assertFalse(timestamp.isBefore(before));
        assertFalse(timestamp.isAfter(Instant.now()));
    }

    @Test
    void databaseFailureReturns503WithoutLeakingSqlOrConnectionDetails() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController(null))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        var response = mvc.perform(get("/test/database-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Channel data unavailable"))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andReturn().getResponse();

        assertFalse(response.getContentAsString().contains("private SQL or connection detail"));
        Instant.parse(new ObjectMapper().readTree(response.getContentAsString()).get("timestamp").asText());
    }

    @TestComponent
    @RestController
    static class FailingController {

        private final ErrorCode errorCode;

        FailingController(ErrorCode errorCode) {
            this.errorCode = errorCode;
        }

        @GetMapping("/test/failure")
        String fail() {
            throw new AppException(errorCode);
        }

        @GetMapping("/test/database-failure")
        String databaseFailure() {
            throw new DataAccessResourceFailureException("private SQL or connection detail");
        }
    }
}
