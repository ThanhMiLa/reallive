package com.reallive.userservice.auth.adapter.kafka;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.adapter.kafka.message.EmailType;
import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class KafkaPasswordResetEmailPublisherTest {

    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private KafkaPasswordResetEmailPublisher publisher;
    private EmailVerificationRequestedMessage message;

    @BeforeEach
    void setUp() {
        publisher = new KafkaPasswordResetEmailPublisher(template, mapper);
        ReflectionTestUtils.setField(publisher, "topic", "test.password-reset.requests");
        message = EmailVerificationRequestedMessage.builder()
                .schemaVersion(1)
                .userId(UUID.randomUUID())
                .emailType(EmailType.PASSWORD_RESET)
                .otp("012345")
                .recipientEmail("test@example.com")
                .build();
    }

    @Test
    void publishesStringJsonWithUserKey() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish(message);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("test.password-reset.requests"), eq(message.getUserId().toString()), json.capture());
        assertEquals("012345", mapper.readTree(json.getValue()).get("otp").asText());
        assertEquals(message.getUserId().toString(), mapper.readTree(json.getValue()).get("userId").asText());
        assertEquals(EmailType.PASSWORD_RESET.name(), mapper.readTree(json.getValue()).get("emailType").asText());
    }

    @Test
    void waitsUntilBrokerAcknowledges() throws Exception {
        CompletableFuture<SendResult<String, String>> acknowledgement = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        when(template.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sent.countDown();
            return acknowledgement;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> publisher.publish(message));
            try {
                assertTrue(sent.await(5, TimeUnit.SECONDS));
                assertFalse(task.isDone());
            } finally {
                acknowledgement.complete(null);
            }
            task.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsUncertaintyWhenSubmissionIsInterrupted() {
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(template.send(anyString(), anyString(), anyString())).thenReturn(pending);
        Thread.currentThread().interrupt();
        try {
            AppException exception = assertThrows(AppException.class, () -> publisher.publish(message));
            assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN, exception.getErrorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void reportsUncertaintyWhenDeliveryTimesOut() {
        CompletableFuture<SendResult<String, String>> timedOut = new CompletableFuture<>();
        timedOut.completeExceptionally(new TimeoutException("Timed out waiting for broker"));
        when(template.send(anyString(), anyString(), anyString())).thenReturn(timedOut);
        AppException exception = assertThrows(AppException.class, () -> publisher.publish(message));
        assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN, exception.getErrorCode());
    }

    @Test
    void reportsPreSubmissionFailureWhenSerializationFails() {
        ReflectionTestUtils.setField(publisher, "topic", "");
        AppException exception = assertThrows(AppException.class, () -> publisher.publish(message));
        assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED, exception.getErrorCode());
    }

    @Test
    void registersProducerLoggingListener() {
        publisher.configureProducerLogging();
        verify(template).setProducerListener(any(ProducerListener.class));
    }
}
