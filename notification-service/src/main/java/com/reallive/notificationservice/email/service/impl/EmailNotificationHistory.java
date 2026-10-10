package com.reallive.notificationservice.email.service.impl;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.dto.response.SendGridSubmissionResult;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.dto.message.EmailType;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;
import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import com.reallive.notificationservice.email.entity.EmailNotificationStatus;
import com.reallive.notificationservice.email.repository.EmailNotificationRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class EmailNotificationHistory {

    EmailNotificationRepository repository;
    EmailDeliveryProperties properties;
    Clock emailClock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<EmailNotificationEntity> find(UUID eventId) {
        return repository.findByEventId(eventId);
    }

    /** Commits unique event history before a provider call; the OTP is never persisted. */
    public EmailNotificationEntity create(EmailVerificationRequestedMessage message) {
        return repository.saveAndFlush(notification(message));
    }

    /**
     * Commits a new attempt under a row lock. Only a returned PROCESSING status grants ownership.
     * Previously processing events become UNKNOWN and cannot acquire another provider call.
     */
    public EmailNotificationEntity claim(EmailVerificationRequestedMessage message) {
        var notification = locked(message.getEventId());
        if (!matches(notification, message)) {
            throw new AppException(ErrorCode.EMAIL_MESSAGE_CONFLICT);
        }
        Instant now = emailClock.instant();
        if (notification.getStatus() == EmailNotificationStatus.PROCESSING) {
            notification.setStatus(EmailNotificationStatus.UNKNOWN);
            notification.setLastError("INTERRUPTED_OR_IN_FLIGHT_SUBMISSION");
        } else if (notification.getStatus() == EmailNotificationStatus.PENDING) {
            if (!notification.getExpiresAt().isAfter(now)) {
                notification.setStatus(EmailNotificationStatus.EXPIRED);
                notification.setNextAttemptAt(null);
            } else if (notification.getAttemptCount() >= maximumAttempts()) {
                notification.setStatus(EmailNotificationStatus.FAILED);
                notification.setLastError("RETRY_LIMIT_REACHED");
                notification.setNextAttemptAt(null);
            } else if (notification.getNextAttemptAt() == null || !notification.getNextAttemptAt().isAfter(now)) {
                notification.setStatus(EmailNotificationStatus.PROCESSING);
                notification.setAttemptCount(notification.getAttemptCount() + 1);
                notification.setNextAttemptAt(null);
            }
        }
        return repository.saveAndFlush(notification);
    }

    /** Persists the provider result only for the current attempt, permitting late confirmation of UNKNOWN. */
    public EmailNotificationEntity complete(UUID eventId, int attempt, SendGridSubmissionResult submission) {
        var notification = locked(eventId);
        if (notification.getAttemptCount() != attempt
                || notification.getStatus() != EmailNotificationStatus.PROCESSING
                && notification.getStatus() != EmailNotificationStatus.UNKNOWN) {
            return notification;
        }
        boolean wasUnknown = notification.getStatus() == EmailNotificationStatus.UNKNOWN;
        notification.setLastHttpStatus(submission.getHttpStatus());
        notification.setLastError(submission.getSafeError());
        notification.setNextAttemptAt(null);
        notification.setStatus(submission.getStatus());
        if (submission.getStatus() == EmailNotificationStatus.ACCEPTED) {
            notification.setProviderMessageId(submission.getProviderMessageId());
            notification.setLastError(null);
        } else if (submission.getStatus() == EmailNotificationStatus.PENDING) {
            scheduleRetry(notification, submission, wasUnknown);
        }
        return repository.saveAndFlush(notification);
    }

    /** Finalizes history before DLT publication; accepted/expired events need no dead-letter copy. */
    public boolean recover(EmailVerificationRequestedMessage message) {
        var notification = repository.findByEventIdForUpdate(message.getEventId()).orElse(null);
        if (notification == null) {
            notification = notification(message);
            notification.setStatus(EmailNotificationStatus.FAILED);
            notification.setLastError("PROCESSING_FAILED_BEFORE_SUBMISSION");
            repository.saveAndFlush(notification);
            return true;
        }
        if (!matches(notification, message)) {
            return true;
        }
        if (notification.getStatus() == EmailNotificationStatus.ACCEPTED
                || notification.getStatus() == EmailNotificationStatus.EXPIRED) {
            return false;
        }
        if (notification.getStatus() == EmailNotificationStatus.PENDING) {
            notification.setStatus(notification.getExpiresAt().isAfter(emailClock.instant())
                    ? EmailNotificationStatus.FAILED : EmailNotificationStatus.EXPIRED);
            notification.setNextAttemptAt(null);
            repository.saveAndFlush(notification);
        } else if (notification.getStatus() == EmailNotificationStatus.PROCESSING) {
            notification.setStatus(EmailNotificationStatus.UNKNOWN);
            notification.setLastError("INTERRUPTED_OR_IN_FLIGHT_SUBMISSION");
            repository.saveAndFlush(notification);
        }
        return notification.getStatus() != EmailNotificationStatus.EXPIRED;
    }

    private void scheduleRetry(EmailNotificationEntity notification, SendGridSubmissionResult submission, boolean wasUnknown) {
        if (wasUnknown || notification.getAttemptCount() >= maximumAttempts()) {
            notification.setStatus(EmailNotificationStatus.FAILED);
            return;
        }
        Instant next = emailClock.instant().plusMillis(properties.getRetryBackoffMs());
        if (submission.getRetryAt() != null && submission.getRetryAt().isAfter(next)) {
            next = submission.getRetryAt();
        }
        if (!notification.getExpiresAt().isAfter(next)) {
            notification.setStatus(EmailNotificationStatus.EXPIRED);
        } else {
            notification.setStatus(EmailNotificationStatus.PENDING);
            notification.setNextAttemptAt(next);
        }
    }

    private long maximumAttempts() {
        return properties.getMaxRetries() == Long.MAX_VALUE ? Long.MAX_VALUE : properties.getMaxRetries() + 1;
    }

    private EmailNotificationEntity locked(UUID eventId) {
        return repository.findByEventIdForUpdate(eventId)
                .orElseThrow(() -> new AppException(ErrorCode.EMAIL_HISTORY_UNAVAILABLE));
    }

    private boolean matches(EmailNotificationEntity notification, EmailVerificationRequestedMessage message) {
        return Objects.equals(notification.getUserId(), message.getUserId())
                && Objects.equals(notification.getEmailType(), message.getEmailType())
                && Objects.equals(notification.getRecipientEmail(), message.getRecipientEmail())
                && Objects.equals(notification.getIssuedAt(), message.getIssuedAt().truncatedTo(ChronoUnit.MICROS))
                && Objects.equals(notification.getExpiresAt(), message.getExpiresAt().truncatedTo(ChronoUnit.MICROS));
    }

    private EmailNotificationEntity notification(EmailVerificationRequestedMessage message) {
        String templateId = message.getEmailType() == EmailType.PASSWORD_RESET
                && properties.getSendgrid().getPasswordResetTemplateId() != null
                && !properties.getSendgrid().getPasswordResetTemplateId().isBlank()
                ? properties.getSendgrid().getPasswordResetTemplateId()
                : properties.getSendgrid().getEmailVerificationTemplateId();
        return EmailNotificationEntity.builder().eventId(message.getEventId()).userId(message.getUserId())
                .emailType(message.getEmailType()).recipientEmail(message.getRecipientEmail())
                .templateId(templateId)
                .issuedAt(message.getIssuedAt().truncatedTo(ChronoUnit.MICROS))
                .expiresAt(message.getExpiresAt().truncatedTo(ChronoUnit.MICROS)).build();
    }
}
