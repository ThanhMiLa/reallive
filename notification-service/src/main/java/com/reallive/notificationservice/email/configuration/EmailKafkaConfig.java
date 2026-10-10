package com.reallive.notificationservice.email.configuration;

import com.reallive.notificationservice.email.consumer.EmailKafkaRecovery;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailDeliveryProperties.class)
public class EmailKafkaConfig {

    public EmailKafkaConfig(KafkaProperties kafkaProperties) {
        if (kafkaProperties.getBootstrapServers().isEmpty()
                || kafkaProperties.getBootstrapServers().stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("KAFKA_BOOTSTRAP_SERVERS must be configured");
        }
        String groupId = kafkaProperties.getConsumer().getGroupId();
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalStateException("EMAIL_VERIFICATION_CONSUMER_GROUP must be configured");
        }
    }

    @Bean
    public NewTopics emailVerificationTopics(EmailDeliveryProperties properties) {
        return new NewTopics(
                TopicBuilder.name(properties.getTopic())
                        .partitions(properties.getPartitions()).replicas(properties.getReplicas())
                        .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(properties.getRetentionMs()))
                        .config(TopicConfig.SEGMENT_MS_CONFIG, "60000")
                        .build(),
                TopicBuilder.name(properties.getDeadLetterTopic())
                        .partitions(properties.getPartitions()).replicas(properties.getReplicas())
                        .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(properties.getRetentionMs()))
                        .config(TopicConfig.SEGMENT_MS_CONFIG, "60000")
                        .build());
    }

    /** A failed record is acknowledged only after the broker accepts its dead-letter copy. */
    @Bean
    public DefaultErrorHandler emailKafkaErrorHandler(KafkaTemplate<String, String> template,
            EmailDeliveryProperties properties, EmailKafkaRecovery recovery) {
        LoggingProducerListener<String, String> listener = new LoggingProducerListener<>();
        listener.setIncludeContents(false);
        template.setProducerListener(listener);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(properties.getDeadLetterTopic(), record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setLogRecoveryRecord(false);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            if (recovery.beforeDeadLetter(record)) {
                recoverer.accept(record, exception);
            }
        },
                new FixedBackOff(properties.getRetryBackoffMs(), properties.getMaxRetries()));
        handler.setBackOffFunction(recovery::backOff);
        handler.setResetStateOnExceptionChange(false);
        return handler;
    }
}
