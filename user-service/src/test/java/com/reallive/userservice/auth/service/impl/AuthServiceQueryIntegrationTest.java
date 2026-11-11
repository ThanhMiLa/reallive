package com.reallive.userservice.auth.service.impl;

import com.reallive.userservice.auth.adapter.kafka.PasswordResetEmailPublisher;
import com.reallive.userservice.auth.adapter.redis.PasswordResetOtpStore;
import com.reallive.userservice.auth.dto.request.ChangePasswordRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.entity.PermissionEntity;
import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.PermissionRepository;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.AuthTokenService;
import com.reallive.userservice.auth.util.PasswordResetOtpHasher;
import com.reallive.userservice.auth.util.UserAuthoritiesMapper;
import com.reallive.userservice.client.wallet.WalletClient;
import com.reallive.userservice.user.repository.ProfileRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_query_regression;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AuthServiceQueryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private EntityManager entityManager;

    private AuthServiceImpl authService;
    private AuthTokenService authTokenService;
    private PasswordEncoder passwordEncoder;
    private Statistics statistics;
    private final TreeSet<String> expectedAuthorities = new TreeSet<>();
    private final AuthResponse tokenResponse = AuthResponse.builder().accessToken("access").refreshToken("refresh").build();

    @BeforeEach
    void setUp() {
        authTokenService = mock(AuthTokenService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authService = new AuthServiceImpl(userRepository, mock(UserMapper.class), passwordEncoder, roleRepository,
                authTokenService, mock(ProfileRepository.class), mock(PasswordResetOtpStore.class),
                mock(PasswordResetEmailPublisher.class), mock(PasswordResetOtpHasher.class), mock(WalletClient.class));
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        when(authTokenService.issue(any(), anyList())).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            assertEquals(List.copyOf(expectedAuthorities), new UserAuthoritiesMapper().map(user));
            return tokenResponse;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void refreshLoadsAllAuthoritiesInOneSelect(int roleCount) {
        UUID userId = createUser(roleCount);
        when(authTokenService.refreshUserId("refresh")).thenReturn(userId);
        when(authTokenService.revocation("refresh", "REFRESH")).thenReturn(List.of());

        assertSame(tokenResponse, authService.refresh("refresh"));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void refreshIncludesUsersWithoutRolesInOneSelect() {
        UUID userId = createUser(0);
        when(authTokenService.refreshUserId("refresh")).thenReturn(userId);
        when(authTokenService.revocation("refresh", "REFRESH")).thenReturn(List.of());

        assertSame(tokenResponse, authService.refresh("refresh"));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void changePasswordLoadsAllAuthoritiesInOneSelect(int roleCount) {
        UUID userId = createUser(roleCount);
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(authTokenService.revocation("access", "ACCESS")).thenReturn(List.of());
        when(authTokenService.revocation("refresh", "REFRESH")).thenReturn(List.of());
        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .currentPassword("old-password").newPassword("new-password").confirmPassword("new-password").build();

        assertSame(tokenResponse, authService.changePassword(userId, "access", "refresh", request));

        assertEquals(1, statistics.getPrepareStatementCount());
        entityManager.flush();
        entityManager.clear();
        assertEquals("new-hash", userRepository.findById(userId).orElseThrow().getPasswordHash());
    }

    private UUID createUser(int roleCount) {
        UserEntity user = UserEntity.builder().username(UUID.randomUUID().toString())
                .email(UUID.randomUUID() + "@example.com").passwordHash("old-hash").build();
        for (int roleIndex = 0; roleIndex < roleCount; roleIndex++) {
            RoleEntity role = RoleEntity.builder().name("ROLE_" + UUID.randomUUID()).permissions(new HashSet<>()).build();
            for (int permissionIndex = 0; permissionIndex < roleIndex; permissionIndex++) {
                PermissionEntity permission = permissionRepository.save(PermissionEntity.builder()
                        .name("PERMISSION_" + UUID.randomUUID()).build());
                role.getPermissions().add(permission);
                expectedAuthorities.add(permission.getName());
            }
            user.getRoles().add(roleRepository.save(role));
            expectedAuthorities.add("ROLE_" + role.getName());
        }
        UUID userId = userRepository.saveAndFlush(user).getId();
        entityManager.clear();
        statistics.clear();
        return userId;
    }
}
