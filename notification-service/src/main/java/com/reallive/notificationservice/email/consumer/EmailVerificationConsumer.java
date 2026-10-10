package com.reallive.notificationservice.email.consumer;

import com.reallive.notificationservice.email.service.EmailVerificationDeliveryService;
import com.reallive.notificationservice.email.util.EmailVerificationMessageReader;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class EmailVerificationConsumer {

    EmailVerificationMessageReader reader;
    EmailVerificationDeliveryService delivery;

    @KafkaListener(id = "email-verification-delivery", topics = "${notification.email.topic}",
            groupId = "${spring.kafka.consumer.group-id}", autoStartup = "${notification.email.consumer-enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        delivery.deliver(reader.read(record.key(), record.value()));
    }
}
