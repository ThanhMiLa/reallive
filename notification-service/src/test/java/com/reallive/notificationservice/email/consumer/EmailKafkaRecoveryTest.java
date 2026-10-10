package com.reallive.notificationservice.email.consumer;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.EmailDeliveryTestSupport;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import com.reallive.notificationservice.email.service.impl.EmailNotificationHistory;
import com.reallive.notificationservice.email.util.EmailVerificationMessageReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailKafkaRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");
    @Mock private EmailVerificationMessageReader reader;
    @Mock private EmailNotificationHistory history;
    private EmailDeliveryProperties properties;
    private EmailKafkaRecovery recovery;
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("email", 0, 0, "user", "payload");

    @BeforeEach
    void setUp() {
        properties = EmailDeliveryTestSupport.properties();
        recovery = new EmailKafkaRecovery(reader, history, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"EMAIL_MESSAGE_INVALID", "EMAIL_MESSAGE_CONFLICT",
            "EMAIL_DELIVERY_REJECTED", "EMAIL_DELIVERY_UNKNOWN", "EMAIL_DELIVERY_RETRY_EXHAUSTED"})
    void wrappedPermanentFailuresDoNotRetry(ErrorCode code) {
        var error = new IllegalStateException("framework wrapper", new AppException(code));
        assertEquals(BackOffExecution.STOP, recovery.backOff(record, error).start().nextBackOff());
        verifyNoInteractions(history);
    }

    @Test
    void backoffReadsUpdatedRetrySchedulesForEachRetryAndStillHasAFiniteBudget() {
        var message = EmailDeliveryTestSupport.message(NOW);
        when(reader.read("user", "payload")).thenReturn(message);
        when(history.find(message.getEventId())).thenReturn(
                Optional.of(EmailNotificationEntity.builder().nextAttemptAt(NOW.plusSeconds(10)).build()),
                Optional.of(EmailNotificationEntity.builder().nextAttemptAt(NOW.plusSeconds(20)).build()));
        var execution = recovery.backOff(record, new AppException(ErrorCode.EMAIL_DELIVERY_RETRYABLE)).start();
        assertEquals(10001, execution.nextBackOff());
        assertEquals(20001, execution.nextBackOff());
        assertEquals(BackOffExecution.STOP, execution.nextBackOff());
    }

    @Test
    void malformedRecordIsDeadLetteredWithoutTryingToPersistIncompleteHistory() {
        when(reader.read("user", "payload")).thenThrow(new AppException(ErrorCode.EMAIL_MESSAGE_INVALID));
        assertTrue(recovery.beforeDeadLetter(record));
        verifyNoInteractions(history);
    }

    @Test
    void historyFailurePreventsSuccessfulRecovery() {
        var message = EmailDeliveryTestSupport.message(NOW);
        when(reader.read("user", "payload")).thenReturn(message);
        when(history.recover(message)).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertEquals(ErrorCode.EMAIL_HISTORY_UNAVAILABLE, assertThrows(AppException.class,
                () -> recovery.beforeDeadLetter(record)).getErrorCode());
    }
}
