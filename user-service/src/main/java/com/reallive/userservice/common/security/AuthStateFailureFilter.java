package com.reallive.userservice.common.security;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

/** Handles auth-state failures before controller exception handling is available. */
public class AuthStateFailureFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (AppException exception) {
            if (exception.getErrorCode() != ErrorCode.AUTH_STATE_UNAVAILABLE) {
                throw exception;
            }
            ErrorCode error = exception.getErrorCode();
            response.setStatus(error.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.builder()
                    .status(error.getHttpStatus().value()).code(error.getCode()).message(error.getMessage())
                    .result(null).timestamp(Instant.now().toString()).build()));
        }
    }
}
