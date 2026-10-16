package com.reallive.paymentservice.wallet.controller.internal;

import com.reallive.paymentservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.common.exception.GlobalExceptionHandler;
import com.reallive.paymentservice.common.security.*;
import com.reallive.paymentservice.wallet.dto.response.WalletCreationResponse;
import com.reallive.paymentservice.wallet.service.WalletService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WalletController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, CustomJwtDecoder.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
class WalletControllerTest {
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private WalletService service;

    @Test
    void internalPostWithoutCookieReturnsCompleteResponseEnvelope() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(service.createWallet(userId)).thenReturn(WalletCreationResponse.builder()
                .id(walletId).userId(userId).balance(0L).build());

        mvc.perform(endpoint(userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("Wallet created successfully"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.result.id").value(walletId.toString()))
                .andExpect(jsonPath("$.result.userId").value(userId.toString()))
                .andExpect(jsonPath("$.result.balance").value(0));
        verify(service).createWallet(userId);
        verifyNoMoreInteractions(service);
    }

    @Test
    void duplicateWalletReturnsConflictEnvelope() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.createWallet(userId)).thenThrow(new AppException(ErrorCode.WALLET_ALREADY_EXISTS));

        mvc.perform(endpoint(userId)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void databaseFailureReturns503WithoutConnectionDetails() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.createWallet(userId)).thenThrow(new DataAccessResourceFailureException("secret SQL details"));

        mvc.perform(endpoint(userId)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Payment data is unavailable"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "null", "12345", "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"})
    void malformedUserIdReturns400WithoutCallingService(String userId) throws Exception {
        mvc.perform(post("/payment/internal/api/wallets/create/" + userId).contextPath("/payment"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void getOnInternalPathStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/payment/internal/api/wallets/create/" + UUID.randomUUID()).contextPath("/payment"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void otherInternalPostDoesNotInheritWalletPermission() throws Exception {
        mvc.perform(post("/payment/internal/api/other-operation").contextPath("/payment"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void publicPathCannotCallInternalController() throws Exception {
        mvc.perform(post("/payment/api/wallets/create/" + UUID.randomUUID()).contextPath("/payment"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void internalEndpointDoesNotAuthenticateUserCookies() throws Exception {
        mvc.perform(endpoint(UUID.randomUUID()).cookie(new Cookie("access_token", "invalid")))
                .andExpect(status().isOk());
        Mockito.verify(service).createWallet(ArgumentMatchers.any());
    }

    private MockHttpServletRequestBuilder endpoint(UUID userId) {
        return post("/payment/internal/api/wallets/create/" + userId).contextPath("/payment");
    }
}
