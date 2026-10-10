package com.reallive.notificationservice.email.consumer;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.service.impl.EmailNotificationHistory;
import com.reallive.notificationservice.email.util.EmailVerificationMessageReader;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;
import java.time.Duration;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class EmailKafkaRecovery {

    EmailVerificationMessageReader reader;
    EmailNotificationHistory history;
    EmailDeliveryProperties properties;
    Clock emailClock;

    public BackOff backOff(ConsumerRecord<?, ?> record, Exception exception) {
        ErrorCode code = errorCode(exception);
        if (code != null && code != ErrorCode.EMAIL_DELIVERY_RETRYABLE && code != ErrorCode.EMAIL_HISTORY_UNAVAILABLE) {
            return new FixedBackOff(0, 0);
        }
        if (code == ErrorCode.EMAIL_DELIVERY_RETRYABLE) {
            return () -> new BackOffExecution() {
                long retries;

                @Override
                public long nextBackOff() {
                    if (retries++ >= properties.getMaxRetries()) {
                        return STOP;
                    }
                    return retryDelay(record);
                }
            };
        }
        return new FixedBackOff(properties.getRetryBackoffMs(), properties.getMaxRetries());
    }

    private long retryDelay(ConsumerRecord<?, ?> record) {
        long delay = properties.getRetryBackoffMs();
        try {
            var message = reader.read((String) record.key(), (String) record.value());
            var notification = history.find(message.getEventId()).orElse(null);
            if (notification != null && notification.getNextAttemptAt() != null) {
                delay = Math.max(delay, Duration.between(emailClock.instant(), notification.getNextAttemptAt()).toMillis() + 1);
            }
        } catch (AppException | DataAccessException ignored) {
            // History is checked again before every attempt; an outage must not permit an early send.
        }
        return Math.min(delay, 60000);
    }

    public boolean beforeDeadLetter(ConsumerRecord<?, ?> record) {
        try {
            var message = reader.read((String) record.key(), (String) record.value());
            return history.recover(message);
        } catch (AppException exception) {
            if (exception.getErrorCode() == ErrorCode.EMAIL_MESSAGE_INVALID) {
                return true;
            }
            throw exception;
        } catch (DataAccessException exception) {
            throw new AppException(ErrorCode.EMAIL_HISTORY_UNAVAILABLE);
        }
    }

    private ErrorCode errorCode(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof AppException appException) {
                return appException.getErrorCode();
            }
        }
        return null;
    }
}
