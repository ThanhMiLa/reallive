package com.reallive.userservice.auth.application.service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.reallive.userservice.auth.application.dto.request.LoginRequest;
import com.reallive.userservice.auth.application.dto.response.LoginResponse;
import com.reallive.userservice.auth.application.dto.response.TokenPair;
import com.reallive.userservice.auth.application.port.out.PasswordVerifierPort;
import com.reallive.userservice.auth.application.port.out.TokenProviderPort;
import com.reallive.userservice.auth.application.port.out.UserRepositoryPort;
import com.reallive.userservice.auth.domain.exception.AccountBannedException;
import com.reallive.userservice.auth.domain.exception.InvalidCredentialsException;
import com.reallive.userservice.auth.domain.model.User;
import com.reallive.userservice.auth.domain.model.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock
    private UserRepositoryPort userRepository;
    @Mock
    private PasswordVerifierPort passwordVerifier;
    @Mock
    private TokenProviderPort tokenProvider;

    private LoginService loginService;

    @BeforeEach
    void setUp() {
        loginService = new LoginService(userRepository, passwordVerifier, tokenProvider);
    }

    @Test
    void normalizesUsernameWithoutChangingPasswordAndAllowsUnverifiedActiveUser() {
        User user = user(UserStatus.ACTIVE);
        String password = " password with spaces ";
        TokenPair tokens = new TokenPair("access", "refresh");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordVerifier.matches(password, "hash")).thenReturn(true);
        when(tokenProvider.issue(user)).thenReturn(tokens);

        LoginResponse login = loginService.execute(new LoginRequest(" ALICE ", password));

        assertThat(login.userId()).isEqualTo(user.id());
        assertThat(login.emailVerified()).isFalse();
        assertThat(login.roles()).containsExactly("USER");
        assertThat(login.tokens()).isSameAs(tokens);
        verify(passwordVerifier).matches(password, "hash");
    }

    @Test
    void unknownUserPerformsDummyPasswordVerificationAndDoesNotIssueTokens() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.execute(new LoginRequest("unknown", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordVerifier).matches("wrong", null);
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void wrongPasswordDoesNotRevealBannedStatusOrIssueTokens() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(UserStatus.BANNED)));

        assertThatThrownBy(() -> loginService.execute(new LoginRequest("alice", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenProvider);
    }

    @Test
    void bannedUserWithCorrectPasswordCannotReceiveTokens() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(UserStatus.BANNED)));
        when(passwordVerifier.matches("correct", "hash")).thenReturn(true);

        assertThatThrownBy(() -> loginService.execute(new LoginRequest("alice", "correct")))
                .isInstanceOf(AccountBannedException.class);

        verifyNoInteractions(tokenProvider);
    }

    private User user(UserStatus status) {
        return new User(UUID.randomUUID(), "alice", "alice@example.com", "hash", status, false, Set.of("USER"));
    }
}
