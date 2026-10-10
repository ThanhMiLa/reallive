package com.reallive.notificationservice.email.configuration;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.admin.auto-create=true", "spring.kafka.listener.auto-startup=true", "notification.email.max-retries=1",
        "notification.email.retry-backoff-ms=10"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(EmailKafkaConfigTest.ListenerConfiguration.class)
@DirtiesContext
class EmailKafkaConfigTest {

    @Autowired
    private KafkaTemplate<String, String> template;

    @Autowired
    private EmailDeliveryProperties properties;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private FailingListener listener;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Test
    void retriesAndCommitsOnlyAfterProcessingOrDurableDeadLetterPublication() throws Exception {
        var container = registry.getListenerContainer("test-email-listener");
        assertThat(container).isNotNull();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(kafkaProperties.getConsumer().getEnableAutoCommit()).isFalse();

        var sent = template.send(properties.getTopic(), "test-user", "simulated-email").get(10, TimeUnit.SECONDS);
        var metadata = sent.getRecordMetadata();
        TopicPartition partition = new TopicPartition(metadata.topic(), metadata.partition());
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            try {
                assertThat(listener.started.await(10, TimeUnit.SECONDS)).isTrue();
                OffsetAndMetadata beforeProcessing = committedOffset(admin, partition);
                assertThat(beforeProcessing == null || beforeProcessing.offset() <= metadata.offset()).isTrue();
            } finally {
                listener.release.countDown();
            }

            Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(broker, "test-dlt-" + UUID.randomUUID(), false);
            try (var consumer = new DefaultKafkaConsumerFactory<>(consumerProperties,
                    new StringDeserializer(), new StringDeserializer()).createConsumer()) {
                consumer.subscribe(List.of(properties.getDeadLetterTopic()));
                ConsumerRecord<String, String> deadLetter = KafkaTestUtils.getSingleRecord(consumer,
                        properties.getDeadLetterTopic(), Duration.ofSeconds(10));
                assertThat(deadLetter.key()).isEqualTo("test-user");
                assertThat(deadLetter.value()).isEqualTo("simulated-email");
                assertThat(listener.attempts.get()).isEqualTo(2);
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(committedOffset(admin, partition).offset()).isEqualTo(metadata.offset() + 1));
        }
    }

    private OffsetAndMetadata committedOffset(Admin admin, TopicPartition partition) throws Exception {
        return admin.listConsumerGroupOffsets("test-email-consumer").partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS).get(partition);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration {

        @Bean
        FailingListener failingListener() {
            return new FailingListener();
        }
    }

    static class FailingListener {
        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @KafkaListener(id = "test-email-listener", groupId = "test-email-consumer", topics = "${notification.email.topic}")
        void receive(String message) throws InterruptedException {
            attempts.incrementAndGet();
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test processing timed out");
            }
            throw new IllegalStateException("Simulated delivery failure");
        }
    }
}
