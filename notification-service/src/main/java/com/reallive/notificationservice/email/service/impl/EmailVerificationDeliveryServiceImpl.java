package com.reallive.notificationservice.email.service.impl;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.client.sendgrid.SendGridClient;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;
import com.reallive.notificationservice.email.dto.response.SendGridSubmissionResult;
import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import com.reallive.notificationservice.email.entity.EmailNotificationStatus;
import com.reallive.notificationservice.email.service.EmailVerificationDeliveryService;
import com.reallive.notificationservice.email.util.SendGridMailRequestBuilder;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class EmailVerificationDeliveryServiceImpl implements EmailVerificationDeliveryService {

    EmailNotificationHistory history;
    SendGridClient client;
    EmailDeliveryProperties properties;
    Clock emailClock;

    @Override
    public void deliver(EmailVerificationRequestedMessage message) {
        try {
            ensureHistory(message);
            var notification = history.claim(message);
            if (notification.getStatus() != EmailNotificationStatus.PROCESSING) {
                requireCompleted(notification);
                return;
            }
            var submission = submit(message, notification);
            requireCompleted(persistSubmission(notification, submission));
        } catch (DataAccessException exception) {
            throw new AppException(ErrorCode.EMAIL_HISTORY_UNAVAILABLE);
        }
    }

    private void ensureHistory(EmailVerificationRequestedMessage message) {
        if (history.find(message.getEventId()).isEmpty()) {
            try {
                history.create(message);
            } catch (DataIntegrityViolationException race) {
                if (history.find(message.getEventId()).isEmpty()) {
                    throw race;
                }
            }
        }
    }

    private EmailNotificationEntity persistSubmission(EmailNotificationEntity notification, SendGridSubmissionResult submission) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                return history.complete(notification.getEventId(), notification.getAttemptCount(), submission);
            } catch (DataAccessException exception) {
                if (retry == 2) {
                    throw exception;
                }
            }
        }
        throw new AppException(ErrorCode.EMAIL_HISTORY_UNAVAILABLE);
    }

    private void requireCompleted(EmailNotificationEntity notification) {
        switch (notification.getStatus()) {
            case ACCEPTED, EXPIRED -> { }
            case PENDING -> throw new AppException(ErrorCode.EMAIL_DELIVERY_RETRYABLE);
            case UNKNOWN, PROCESSING -> throw new AppException(ErrorCode.EMAIL_DELIVERY_UNKNOWN);
            case FAILED -> throw new AppException(notification.getAttemptCount() > properties.getMaxRetries()
                    ? ErrorCode.EMAIL_DELIVERY_RETRY_EXHAUSTED : ErrorCode.EMAIL_DELIVERY_REJECTED);
        }
    }

    private SendGridSubmissionResult submit(EmailVerificationRequestedMessage message, EmailNotificationEntity notification) {
        Duration remaining = Duration.between(emailClock.instant(), notification.getExpiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            return outcome(EmailNotificationStatus.EXPIRED, "OTP_EXPIRED");
        }
        Duration timeout = remaining.compareTo(properties.getSendgrid().getRequestTimeout()) < 0
                ? remaining : properties.getSendgrid().getRequestTimeout();
        var request = SendGridMailRequestBuilder.build(message, notification.getTemplateId(), properties.getSendgrid());
        var observed = new AtomicReference<SendGridSubmissionResult>();
        try {
            var result = client.send(request, response -> readResponse(response, observed)).block(timeout);
            return result == null ? outcome(EmailNotificationStatus.UNKNOWN, "EMPTY_HTTP_OUTCOME") : result;
        } catch (RuntimeException exception) {
            if (observed.get() != null) {
                return observed.get();
            }
            if (failedBeforeSending(exception)) {
                return outcome(EmailNotificationStatus.PENDING, "CONNECT_FAILED");
            }
            return outcome(EmailNotificationStatus.UNKNOWN, "HTTP_OUTCOME_UNKNOWN");
        }
    }

    private Mono<SendGridSubmissionResult> readResponse(ClientResponse response,
            AtomicReference<SendGridSubmissionResult> observed) {
        short status = (short) response.statusCode().value();
        if (status == 202) {
            var accepted = SendGridSubmissionResult.builder().status(EmailNotificationStatus.ACCEPTED)
                    .httpStatus(status).providerMessageId(response.headers().header("X-Message-ID")
                            .stream().findFirst().orElse(null)).build();
            observed.set(accepted);
            return response.releaseBody().onErrorComplete().thenReturn(accepted);
        }
        var result = SendGridSubmissionResult.builder().httpStatus(status)
                .status(status == 429 || status >= 500 && status < 600
                        ? EmailNotificationStatus.PENDING : status >= 200 && status < 300
                        ? EmailNotificationStatus.UNKNOWN : EmailNotificationStatus.FAILED)
                .safeError("SENDGRID_HTTP_" + status)
                .retryAt(retryAt(response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER))).build();
        observed.set(result);
        return response.releaseBody().onErrorComplete().thenReturn(result);
    }

    private Instant retryAt(String header) {
        if (header == null) {
            return null;
        }
        try {
            return emailClock.instant().plusSeconds(Math.max(0, Long.parseLong(header.trim())));
        } catch (RuntimeException ignored) {
            try {
                return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException invalidHeader) {
                return null;
            }
        }
    }

    private boolean failedBeforeSending(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private SendGridSubmissionResult outcome(EmailNotificationStatus outcome, String safeError) {
        return SendGridSubmissionResult.builder().status(outcome).safeError(safeError).build();
    }
}
