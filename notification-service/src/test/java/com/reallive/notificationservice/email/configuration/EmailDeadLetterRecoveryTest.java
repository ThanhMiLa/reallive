package com.reallive.notificationservice.email.configuration;

import com.reallive.notificationservice.email.EmailDeliveryTestSupport;
import com.reallive.notificationservice.email.consumer.EmailKafkaRecovery;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EmailDeadLetterRecoveryTest {

    @Mock private KafkaTemplate<String, String> template;
    @Mock private EmailKafkaRecovery recovery;
    @Mock private Consumer<String, String> consumer;
    @Mock private MessageListenerContainer container;
    private DefaultErrorHandler handler;
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("email", 1, 0,
            "user", "PRIVATE_OTP_012345");

    @BeforeEach
    void setUp() {
        var properties = EmailDeliveryTestSupport.properties();
        properties.setDeadLetterTopic("email.dlt");
        properties.setMaxRetries(0);
        var kafka = new KafkaProperties();
        kafka.getConsumer().setGroupId("test-recovery");
        handler = new EmailKafkaConfig(kafka).emailKafkaErrorHandler(template, properties, recovery);
    }

    @Test
    void successfulRecoveryWaitsForHistoryAndBrokerAcknowledgement() {
        when(recovery.beforeDeadLetter(record)).thenReturn(true);
        when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> outbound = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new SendResult<>(outbound,
                    new RecordMetadata(new TopicPartition("email.dlt", 1), 0, 0, 0, 0, 0)));
        });
        assertTrue(handler.handleOne(new IllegalStateException("safe failure"), record, consumer, container));
        var order = inOrder(recovery, template);
        order.verify(recovery).beforeDeadLetter(record);
        order.verify(template).send(any(ProducerRecord.class));
        verify(consumer, never()).commitSync();
    }

    @Test
    void failedDeadLetterPublicationIsNotRecoveredAndDoesNotLogPayload(CapturedOutput output) {
        when(recovery.beforeDeadLetter(record)).thenReturn(true);
        when(template.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertFalse(handler.handleOne(new IllegalStateException("safe failure"), record, consumer, container));
        assertFalse(output.getAll().contains("PRIVATE_OTP_012345"));
    }

    @Test
    void failedHistoryFinalizationPreventsDeadLetterPublication() {
        doThrow(new IllegalStateException("history unavailable")).when(recovery).beforeDeadLetter(record);
        assertFalse(handler.handleOne(new IllegalStateException("safe failure"), record, consumer, container));
        verify(template, never()).send(any(ProducerRecord.class));
    }

    @Test
    void alreadyAcceptedRecordIsRecoveredWithoutSendingAnotherDeadLetter() {
        when(recovery.beforeDeadLetter(record)).thenReturn(false);
        assertTrue(handler.handleOne(new IllegalStateException("safe failure"), record, consumer, container));
        verify(template, never()).send(any(ProducerRecord.class));
    }
}
