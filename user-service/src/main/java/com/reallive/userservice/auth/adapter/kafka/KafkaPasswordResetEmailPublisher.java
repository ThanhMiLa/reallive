package com.reallive.userservice.auth.adapter.kafka;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class KafkaPasswordResetEmailPublisher implements PasswordResetEmailPublisher {

    KafkaTemplate<String, String> kafkaTemplate;
    ObjectMapper objectMapper;

    @NonFinal
    @Value("${email-verification.topic:notification.email-verification.requested.v1}")
    String topic;

    @PostConstruct
    void configureProducerLogging() {
        LoggingProducerListener<String, String> listener = new LoggingProducerListener<>();
        listener.setIncludeContents(false);
        kafkaTemplate.setProducerListener(listener);
    }

    @Override
    public void publish(EmailVerificationRequestedMessage message) {
        String payload = serialize(message);
        try {
            kafkaTemplate.send(topic, message.getUserId().toString(), payload).get(20, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            throw new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN);
        }
    }

    private String serialize(EmailVerificationRequestedMessage message) {
        try {
            if (topic == null || topic.isBlank() || message == null || message.getUserId() == null) {
                throw new IllegalArgumentException("Email publication requires a topic and user identity");
            }
            return objectMapper.writeValueAsString(message);
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED);
        }
    }
}
