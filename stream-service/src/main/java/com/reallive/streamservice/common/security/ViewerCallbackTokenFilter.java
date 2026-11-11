package com.reallive.streamservice.common.security;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ViewerCallbackTokenFilter extends OncePerRequestFilter {

    static final String TOKEN_HEADER = "X-Internal-Token";

    String expectedToken;
    ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!matches(request.getHeader(TOKEN_HEADER))) {
            unauthorized(response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "media-hook", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    private boolean matches(String providedToken) {
        if (providedToken == null || providedToken.isBlank() || expectedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(digest(expectedToken), digest(providedToken));
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.UNAUTHENTICATED;
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
        response.flushBuffer();
    }
}
