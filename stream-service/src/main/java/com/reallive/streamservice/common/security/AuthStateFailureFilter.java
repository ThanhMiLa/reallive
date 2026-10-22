package com.reallive.streamservice.common.security;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

/**
 * Handles auth-state failures before controller exception handling is available.
 */
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthStateFailureFilter extends OncePerRequestFilter {

    ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (AppException exception) {
            if (exception.getErrorCode() != ErrorCode.AUTH_STATE_UNAVAILABLE) {
                throw exception;
            }
            ErrorCode errorCode = exception.getErrorCode();
            ApiResponse<Object> errorResponse = ApiResponse.builder()
                    .status(errorCode.getHttpStatus().value())
                    .code(errorCode.getCode())
                    .message(errorCode.getMessage())
                    .result(null)
                    .timestamp(Instant.now().toString())
                    .build();

            response.setStatus(errorCode.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        }
    }
}
