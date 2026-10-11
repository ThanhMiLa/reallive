package com.reallive.paymentservice.common.security;

import com.reallive.paymentservice.common.dto.ApiResponse;
import com.reallive.paymentservice.common.exception.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

@Slf4j
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {
    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;

        ApiResponse<Object> errorResponse = ApiResponse.builder()
                .timestamp(Instant.now().toString())
                .status(errorCode.getHttpStatus().value())
                .message(errorCode.getMessage())
                .code(errorCode.getCode())
                .result(null)
                .build();

        ObjectMapper objectMapper = new ObjectMapper();
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        response.flushBuffer();
    }
}
