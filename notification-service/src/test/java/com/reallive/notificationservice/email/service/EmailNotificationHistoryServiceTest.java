package com.reallive.notificationservice.email.service;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.EmailDeliveryTestSupport;
import com.reallive.notificationservice.email.dto.response.SendGridSubmissionResult;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;
import com.reallive.notificationservice.email.entity.EmailNotificationStatus;
import com.reallive.notificationservice.email.repository.EmailNotificationRepository;
import com.reallive.notificationservice.email.service.impl.EmailNotificationHistory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
class EmailNotificationHistoryServiceTest {

    private static final String SCHEMA = "email_claims_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");

    @Autowired private EmailNotificationHistory history;
    @MockitoSpyBean private EmailNotificationRepository repository;
    @MockitoBean private Clock emailClock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("notification.test.database-url",
                "jdbc:h2:mem:email_claims;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("notification.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("notification.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @AfterAll
    static void cleanSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @BeforeEach
    void setUp() {
        when(emailClock.instant()).thenReturn(NOW);
    }

    @Test
    void committedCreationSelectsTemplateWithoutPersistingOtpAndSupportsTimestampPrecision() {
        var message = EmailDeliveryTestSupport.message(NOW.plusNanos(123456789));
        var created = history.create(message);
        assertEquals("d-test-template", created.getTemplateId());
        assertEquals(0, created.getAttemptCount());
        var reloaded = history.find(message.getEventId()).orElseThrow();
        assertEquals(NOW.plusNanos(123456000), reloaded.getIssuedAt());
        assertEquals(EmailNotificationStatus.PROCESSING, history.claim(message).getStatus());
    }

    @Test
    void committedCreationSelectsPasswordResetTemplateWhenEmailTypeIsPasswordReset() {
        var message = EmailDeliveryTestSupport.message(NOW);
        message.setEmailType(com.reallive.notificationservice.email.dto.message.EmailType.PASSWORD_RESET);
        var created = history.create(message);
        assertEquals("d-test-password-reset-template", created.getTemplateId());
    }

    @Test
    void simultaneousClaimsAllowOnlyOneProviderOwner() throws Exception {
        var message = create();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return history.claim(message); });
            var second = executor.submit(() -> { start.await(); return history.claim(message); });
            start.countDown();
            var firstClaim = first.get(10, TimeUnit.SECONDS);
            var secondClaim = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, (firstClaim.getStatus() == EmailNotificationStatus.PROCESSING ? 1 : 0)
                    + (secondClaim.getStatus() == EmailNotificationStatus.PROCESSING ? 1 : 0));
            assertEquals(1, history.find(message.getEventId()).orElseThrow().getAttemptCount());
            assertEquals(EmailNotificationStatus.UNKNOWN, history.find(message.getEventId()).orElseThrow().getStatus());
        }
    }

    @Test
    void interruptedAttemptIsNotReclaimedAndLate202CanConfirmItsOutcome() {
        var message = create();
        assertEquals(EmailNotificationStatus.PROCESSING, history.claim(message).getStatus());
        assertFalse(history.claim(message).getStatus() == EmailNotificationStatus.PROCESSING);
        assertEquals(EmailNotificationStatus.UNKNOWN, history.find(message.getEventId()).orElseThrow().getStatus());
        var completed = history.complete(message.getEventId(), 1, result(EmailNotificationStatus.ACCEPTED, 202));
        assertEquals(EmailNotificationStatus.ACCEPTED, completed.getStatus());
        assertEquals("provider-id", completed.getProviderMessageId());
    }

    @Test
    void unknownAttemptCannotBeReopenedByALateRetryableResponse() {
        var message = create();
        history.claim(message);
        history.claim(message);
        assertEquals(EmailNotificationStatus.FAILED,
                history.complete(message.getEventId(), 1, result(EmailNotificationStatus.PENDING, 503)).getStatus());
        assertFalse(history.claim(message).getStatus() == EmailNotificationStatus.PROCESSING);
    }

    @Test
    void acceptedDuplicatesRemainAcceptedAfterTheirOriginalOtpExpires() {
        var message = create();
        history.claim(message);
        history.complete(message.getEventId(), 1, result(EmailNotificationStatus.ACCEPTED, 202));
        when(emailClock.instant()).thenReturn(NOW.plusSeconds(120));
        assertFalse(history.claim(message).getStatus() == EmailNotificationStatus.PROCESSING);
        assertFalse(history.recover(message));
        assertEquals(EmailNotificationStatus.ACCEPTED, history.find(message.getEventId()).orElseThrow().getStatus());
    }

    @Test
    void expiredRequestDoesNotAcquireAnAttempt() {
        var message = EmailDeliveryTestSupport.message(NOW.minusSeconds(60));
        history.create(message);
        var claim = history.claim(message);
        assertFalse(claim.getStatus() == EmailNotificationStatus.PROCESSING);
        assertEquals(EmailNotificationStatus.EXPIRED, claim.getStatus());
        assertEquals(0, claim.getAttemptCount());
        assertFalse(history.recover(message));
    }

    @Test
    void conflictingDuplicateDoesNotModifyOriginalHistory() {
        var message = create();
        message.setRecipientEmail("different@example.com");
        assertEquals(ErrorCode.EMAIL_MESSAGE_CONFLICT, assertThrows(AppException.class, () -> history.claim(message)).getErrorCode());
        assertTrue(history.recover(message));
        var original = history.find(message.getEventId()).orElseThrow();
        assertEquals("recipient@example.com", original.getRecipientEmail());
        assertEquals(EmailNotificationStatus.PENDING, original.getStatus());
        assertEquals(0, original.getAttemptCount());
    }

    @Test
    void retryAfterSurvivesReloadAndPreventsAnEarlyClaim() {
        var message = create();
        history.claim(message);
        var result = result(EmailNotificationStatus.PENDING, 429);
        result.setRetryAt(NOW.plusSeconds(20));
        history.complete(message.getEventId(), 1, result);
        assertEquals(NOW.plusSeconds(20), history.find(message.getEventId()).orElseThrow().getNextAttemptAt());
        assertFalse(history.claim(message).getStatus() == EmailNotificationStatus.PROCESSING);
        when(emailClock.instant()).thenReturn(NOW.plusSeconds(20));
        assertEquals(EmailNotificationStatus.PROCESSING, history.claim(message).getStatus());
        assertEquals(2, history.find(message.getEventId()).orElseThrow().getAttemptCount());
    }

    @Test
    void retryAfterBeyondOtpLifetimeExpiresRequestInsteadOfSchedulingIt() {
        var message = create();
        history.claim(message);
        var result = result(EmailNotificationStatus.PENDING, 429);
        result.setRetryAt(NOW.plusSeconds(120));
        assertEquals(EmailNotificationStatus.EXPIRED, history.complete(message.getEventId(), 1, result).getStatus());
        assertFalse(history.claim(message).getStatus() == EmailNotificationStatus.PROCESSING);
    }

    @Test
    void durableAttemptLimitStopsRetriesAcrossRedelivery() {
        var message = create();
        for (int attempt = 1; attempt <= 3; attempt++) {
            when(emailClock.instant()).thenReturn(NOW.plusSeconds(attempt));
            assertEquals(EmailNotificationStatus.PROCESSING, history.claim(message).getStatus());
            history.complete(message.getEventId(), attempt, result(EmailNotificationStatus.PENDING, 503));
        }
        var exhausted = history.find(message.getEventId()).orElseThrow();
        assertEquals(3, exhausted.getAttemptCount());
        assertEquals(EmailNotificationStatus.FAILED, exhausted.getStatus());
        assertFalse(history.claim(message).getStatus() == EmailNotificationStatus.PROCESSING);
        assertTrue(history.recover(message));
        assertTrue(history.recover(message));
    }

    @Test
    void completionFromAnOlderAttemptCannotOverwriteCurrentAttempt() {
        var message = create();
        history.claim(message);
        history.complete(message.getEventId(), 1, result(EmailNotificationStatus.PENDING, 503));
        when(emailClock.instant()).thenReturn(NOW.plusSeconds(1));
        history.claim(message);
        history.complete(message.getEventId(), 1, result(EmailNotificationStatus.ACCEPTED, 202));
        assertEquals(EmailNotificationStatus.PROCESSING, history.find(message.getEventId()).orElseThrow().getStatus());
        assertEquals(2, history.find(message.getEventId()).orElseThrow().getAttemptCount());
    }

    @Test
    void recoveryLeavesFailedAndUnknownEventsEligibleForRepeatedDeadLetterPublication() {
        var message = create();
        history.claim(message);
        history.complete(message.getEventId(), 1, result(EmailNotificationStatus.UNKNOWN, null));
        assertTrue(history.recover(message));
        assertTrue(history.recover(message));
        assertFalse(history.claim(message).getStatus() == EmailNotificationStatus.PROCESSING);
        assertEquals(EmailNotificationStatus.UNKNOWN, history.find(message.getEventId()).orElseThrow().getStatus());
    }

    @Test
    void recoveryFinalizesPendingHistoryBeforeAllowingDeadLetterPublication() {
        var message = create();
        assertTrue(history.recover(message));
        assertEquals(EmailNotificationStatus.FAILED, history.find(message.getEventId()).orElseThrow().getStatus());
        assertTrue(history.recover(message));
        assertEquals(0, history.find(message.getEventId()).orElseThrow().getAttemptCount());
    }

    @Test
    void recoveryCanRecordFailureBeforeTheFirstSuccessfulInsert() {
        var message = EmailDeliveryTestSupport.message(NOW);
        assertTrue(history.recover(message));
        assertEquals(EmailNotificationStatus.FAILED, history.find(message.getEventId()).orElseThrow().getStatus());
    }

    @Test
    void failedClaimFlushRollsBackStatusAndAttemptCount() {
        var message = create();
        doThrow(new DataAccessResourceFailureException("simulated flush failure"))
                .when(repository).saveAndFlush(argThat(notification -> message.getEventId().equals(notification.getEventId())));
        assertThrows(DataAccessResourceFailureException.class, () -> history.claim(message));
        var unchanged = history.find(message.getEventId()).orElseThrow();
        assertEquals(EmailNotificationStatus.PENDING, unchanged.getStatus());
        assertEquals(0, unchanged.getAttemptCount());
    }

    private EmailVerificationRequestedMessage create() {
        var message = EmailDeliveryTestSupport.message(NOW);
        history.create(message);
        return message;
    }

    private SendGridSubmissionResult result(EmailNotificationStatus status, Integer httpStatus) {
        return SendGridSubmissionResult.builder().status(status).httpStatus(httpStatus == null ? null : httpStatus.shortValue())
                .providerMessageId(status == EmailNotificationStatus.ACCEPTED ? "provider-id" : null)
                .safeError(status == EmailNotificationStatus.ACCEPTED ? null : "SAFE_TEST_FAILURE").build();
    }
}
