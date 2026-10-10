package com.reallive.userservice.user.adapter.kafka;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class KafkaEmailVerificationEmailPublisherTest {

    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private KafkaEmailVerificationEmailPublisher publisher;
    private EmailVerificationRequestedMessage message;

    @BeforeEach
    void setUp() {
        publisher = new KafkaEmailVerificationEmailPublisher(template, mapper);
        ReflectionTestUtils.setField(publisher, "topic", "test.email.requests");
        message = EmailVerificationRequestedMessage.builder().schemaVersion(1)
                .userId(UUID.randomUUID()).otp("012345").recipientEmail("test@example.com").build();
    }

    @Test
    void publishesStringJsonWithUserKey() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish(message);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("test.email.requests"), eq(message.getUserId().toString()), json.capture());
        assertEquals("012345", mapper.readTree(json.getValue()).get("otp").asText());
        assertEquals(message.getUserId().toString(), mapper.readTree(json.getValue()).get("userId").asText());
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
    void serializationFailureIsKnownNotToHavePublished() {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new IllegalArgumentException("test serialization"));
        publisher = new KafkaEmailVerificationEmailPublisher(template, failingMapper);
        ReflectionTestUtils.setField(publisher, "topic", "test.email.requests");
        AppException exception = assertThrows(AppException.class, () -> publisher.publish(message));
        assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED, exception.getErrorCode());
        assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED.getMessage(), exception.getMessage());
        verifyNoInteractions(template);
    }

    @Test
    void missingTopicFailsBeforeSending() {
        ReflectionTestUtils.setField(publisher, "topic", "");
        assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED, assertThrows(AppException.class,
                () -> publisher.publish(message)).getErrorCode());
        verifyNoInteractions(template);
    }

    @Test
    void failedAcknowledgementIsConservativelyUncertain() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("test broker failure")));
        assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN, assertThrows(AppException.class,
                () -> publisher.publish(message)).getErrorCode());
    }

    @Test
    void boundedWaitTimeoutIsUncertain() throws Exception {
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(20, TimeUnit.SECONDS)).thenThrow(new TimeoutException("test timeout"));
        when(template.send(anyString(), anyString(), anyString())).thenReturn(future);
        AppException exception = assertThrows(AppException.class, () -> publisher.publish(message));
        assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN, exception.getErrorCode());
    }

    @Test
    void synchronousSendFailureDoesNotClaimBrokerAbsence() {
        when(template.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("send"));
        assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN, assertThrows(AppException.class,
                () -> publisher.publish(message)).getErrorCode());
    }

    @Test
    void interruptionRestoresThreadFlagAndKeepsUncertainty() throws Exception {
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(20, TimeUnit.SECONDS)).thenThrow(new InterruptedException("test interruption"));
        when(template.send(anyString(), anyString(), anyString())).thenReturn(future);
        try {
            assertEquals(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN, assertThrows(AppException.class,
                    () -> publisher.publish(message)).getErrorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void producerErrorListenerDoesNotLogOtpOrRawJson(CapturedOutput output) {
        publisher.configureProducerLogging();
        ArgumentCaptor<ProducerListener<String, String>> captor = ArgumentCaptor.forClass(ProducerListener.class);
        verify(template).setProducerListener(captor.capture());
        captor.getValue().onError(new ProducerRecord<>("test.email.requests", "user-key",
                "{\"otp\":\"012345\",\"recipientEmail\":\"private@example.com\"}"), null,
                new IllegalStateException("simulated broker error"));
        assertFalse(output.getAll().contains("012345"));
        assertFalse(output.getAll().contains("private@example.com"));
        assertTrue(output.getAll().contains("Exception thrown when sending a message"));
    }
}
