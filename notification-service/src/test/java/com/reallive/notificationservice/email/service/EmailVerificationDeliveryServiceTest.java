package com.reallive.notificationservice.email.service;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.EmailDeliveryTestSupport;
import com.reallive.notificationservice.email.client.sendgrid.SendGridClient;
import com.reallive.notificationservice.email.client.sendgrid.dto.request.SendGridMailRequest;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;
import com.reallive.notificationservice.email.dto.response.SendGridSubmissionResult;
import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import com.reallive.notificationservice.email.entity.EmailNotificationStatus;
import com.reallive.notificationservice.email.service.impl.EmailNotificationHistory;
import com.reallive.notificationservice.email.service.impl.EmailVerificationDeliveryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");
    @Mock private EmailNotificationHistory history;
    @Mock private SendGridClient client;
    private EmailVerificationDeliveryServiceImpl service;
    private EmailDeliveryProperties properties;
    private EmailVerificationRequestedMessage message;
    private EmailNotificationEntity notification;
    private SendGridSubmissionResult accepted;

    @BeforeEach
    void setUp() {
        properties = EmailDeliveryTestSupport.properties();
        service = new EmailVerificationDeliveryServiceImpl(history, client, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        message = EmailDeliveryTestSupport.message(NOW);
        notification = EmailNotificationEntity.builder().eventId(message.getEventId()).attemptCount(1)
                .expiresAt(message.getExpiresAt()).templateId("d-stored-template").status(EmailNotificationStatus.PROCESSING).build();
        accepted = SendGridSubmissionResult.builder().status(EmailNotificationStatus.ACCEPTED).httpStatus((short) 202).build();
    }

    @Test
    void buildsPayloadAndClaimsHistoryBeforeCallingProviderAndCompletesBeforeReturning() {
        when(history.find(message.getEventId())).thenReturn(Optional.empty());
        when(history.create(message)).thenReturn(notification);
        when(history.claim(message)).thenReturn(notification);
        respond(ClientResponse.create(HttpStatusCode.valueOf(202)).build());
        when(history.complete(message.getEventId(), 1, accepted)).thenReturn(completed(EmailNotificationStatus.ACCEPTED));
        service.deliver(message);
        var request = ArgumentCaptor.forClass(SendGridMailRequest.class);
        var order = inOrder(history, client);
        order.verify(history).create(message);
        order.verify(history).claim(message);
        order.verify(client).send(request.capture(), any());
        order.verify(history).complete(message.getEventId(), 1, accepted);
        var payload = request.getValue();
        var personalization = payload.getPersonalizations().getFirst();
        assertEquals("d-stored-template", payload.getTemplateId());
        assertEquals("sender@example.com", payload.getFrom().getEmail());
        assertEquals("RealLive", payload.getFrom().getName());
        assertEquals("recipient@example.com", personalization.getTo().getFirst().getEmail());
        assertEquals("012345", personalization.getDynamicTemplateData().getOtp());
        assertEquals(1, personalization.getDynamicTemplateData().getExpiresInMinutes());
        assertEquals(Map.of("eventId", message.getEventId().toString()), personalization.getCustomArgs());
        assertFalse(payload.toString().contains("012345"));
    }

    @Test
    void acceptedDuplicateNeverCallsProvider() {
        notification.setStatus(EmailNotificationStatus.ACCEPTED);
        prepareAttempt();
        service.deliver(message);
        verifyNoInteractions(client);
    }

    @Test
    void failedDuplicateStillRaisesRecoveryWithoutCallingProvider() {
        notification.setStatus(EmailNotificationStatus.FAILED);
        prepareAttempt();
        assertError(ErrorCode.EMAIL_DELIVERY_REJECTED);
        verifyNoInteractions(client);
    }

    @Test
    void retriesOnlyDatabaseCompletionAfterProviderAccepts() {
        prepareAttempt();
        respond(ClientResponse.create(HttpStatusCode.valueOf(202)).build());
        when(history.complete(message.getEventId(), 1, accepted))
                .thenThrow(new DataAccessResourceFailureException("simulated database outage"))
                .thenReturn(completed(EmailNotificationStatus.ACCEPTED));
        service.deliver(message);
        verify(client, times(1)).send(any(), any());
        verify(history, times(2)).complete(message.getEventId(), 1, accepted);
    }

    @Test
    void databaseFailureBeforeClaimPreventsProviderCall() {
        when(history.find(message.getEventId())).thenThrow(new DataAccessResourceFailureException("database outage"));
        assertError(ErrorCode.EMAIL_HISTORY_UNAVAILABLE);
        verifyNoInteractions(client);
    }

    @Test
    void concurrentInsertRaceLoadsCommittedWinnerBeforeClaim() {
        when(history.find(message.getEventId())).thenReturn(Optional.empty(), Optional.of(notification));
        when(history.create(message)).thenThrow(new DataIntegrityViolationException("duplicate event"));
        notification.setStatus(EmailNotificationStatus.ACCEPTED);
        when(history.claim(message)).thenReturn(notification);
        service.deliver(message);
        verify(history, times(2)).find(message.getEventId());
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 413, 422, 302, 429, 500, 503, 200, 204})
    void classifiesHttpResponsesWithoutPersistingRawProviderBody(int httpStatus) {
        prepareAttempt();
        respond(ClientResponse.create(HttpStatusCode.valueOf(httpStatus)).header("Retry-After", "20")
                .body("private-012345-test-sendgrid-key").build());
        completeWithSubmissionStatus();
        boolean retryable = httpStatus == 429 || httpStatus >= 500;
        boolean unknown = httpStatus == 200 || httpStatus == 204;
        assertError(retryable ? ErrorCode.EMAIL_DELIVERY_RETRYABLE
                : unknown ? ErrorCode.EMAIL_DELIVERY_UNKNOWN : ErrorCode.EMAIL_DELIVERY_REJECTED);
        var submission = capturedSubmission();
        assertEquals(retryable ? EmailNotificationStatus.PENDING
                : unknown ? EmailNotificationStatus.UNKNOWN : EmailNotificationStatus.FAILED, submission.getStatus());
        assertEquals(Short.valueOf((short) httpStatus), submission.getHttpStatus());
        assertEquals(NOW.plusSeconds(20), submission.getRetryAt());
        assertEquals("SENDGRID_HTTP_" + httpStatus, submission.getSafeError());
        assertFalse(submission.toString().contains("012345"));
        assertFalse(submission.toString().contains("test-sendgrid-key"));
    }

    @Test
    void parsesHttpDateRetryAfterInService() {
        prepareAttempt();
        respond(ClientResponse.create(HttpStatusCode.valueOf(429)).header("Retry-After", "Sat, 10 Oct 2026 01:00:20 GMT").build());
        completeWithSubmissionStatus();
        assertError(ErrorCode.EMAIL_DELIVERY_RETRYABLE);
        assertEquals(NOW.plusSeconds(20), capturedSubmission().getRetryAt());
    }

    @Test
    void expiredOtpNeverCallsClient() {
        notification.setExpiresAt(NOW);
        prepareAttempt();
        completeWithSubmissionStatus();
        service.deliver(message);
        verifyNoInteractions(client);
        assertEquals(EmailNotificationStatus.EXPIRED, capturedSubmission().getStatus());
    }

    @Test
    void connectionFailureBeforeSendingCanBeRetried() {
        prepareAttempt();
        when(client.<SendGridSubmissionResult>send(any(), any()))
                .thenReturn(Mono.error(new RuntimeException(new ConnectException("private details"))));
        completeWithSubmissionStatus();
        assertError(ErrorCode.EMAIL_DELIVERY_RETRYABLE);
        assertEquals("CONNECT_FAILED", capturedSubmission().getSafeError());
    }

    @Test
    void serviceBoundsTimeoutAndRecordsUnknownWithoutResending() {
        prepareAttempt();
        properties.getSendgrid().setRequestTimeout(Duration.ofMillis(20));
        when(client.<SendGridSubmissionResult>send(any(), any())).thenReturn(Mono.never());
        completeWithSubmissionStatus();
        assertError(ErrorCode.EMAIL_DELIVERY_UNKNOWN);
        verify(client, times(1)).send(any(), any());
        assertEquals("HTTP_OUTCOME_UNKNOWN", capturedSubmission().getSafeError());
    }

    @Test
    void known202IsRetainedEvenWhenDrainingBodyTimesOut() {
        prepareAttempt();
        properties.getSendgrid().setRequestTimeout(Duration.ofMillis(20));
        respond(ClientResponse.create(HttpStatusCode.valueOf(202)).header("X-Message-ID", "provider-id").body(Flux.never()).build());
        completeWithSubmissionStatus();
        service.deliver(message);
        assertEquals(EmailNotificationStatus.ACCEPTED, capturedSubmission().getStatus());
        assertEquals("provider-id", capturedSubmission().getProviderMessageId());
        verify(client, times(1)).send(any(), any());
    }

    private void prepareAttempt() {
        when(history.find(message.getEventId())).thenReturn(Optional.of(notification));
        when(history.claim(message)).thenReturn(notification);
    }

    private void respond(ClientResponse response) {
        when(client.<SendGridSubmissionResult>send(any(), any())).thenAnswer(invocation -> {
            Function<ClientResponse, Mono<SendGridSubmissionResult>> handler = invocation.getArgument(1);
            return handler.apply(response);
        });
    }

    private void completeWithSubmissionStatus() {
        when(history.complete(eq(message.getEventId()), eq(1), any()))
                .thenAnswer(invocation -> completed(invocation.<SendGridSubmissionResult>getArgument(2).getStatus()));
    }

    private EmailNotificationEntity completed(EmailNotificationStatus status) {
        return EmailNotificationEntity.builder().status(status).attemptCount(1).build();
    }

    private SendGridSubmissionResult capturedSubmission() {
        var submission = ArgumentCaptor.forClass(SendGridSubmissionResult.class);
        verify(history).complete(eq(message.getEventId()), eq(1), submission.capture());
        return submission.getValue();
    }

    private void assertError(ErrorCode code) {
        assertEquals(code, assertThrows(AppException.class, () -> service.deliver(message)).getErrorCode());
    }
}
