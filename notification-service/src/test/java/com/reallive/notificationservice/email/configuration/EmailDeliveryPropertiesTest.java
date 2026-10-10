package com.reallive.notificationservice.email.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailDeliveryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("notification.email.topic=email-requests",
                    "notification.email.dead-letter-topic=email-requests.dlt",
                    "notification.email.sendgrid.api-key=test-sendgrid-key",
                    "notification.email.sendgrid.sender-email=sender@example.com",
                    "notification.email.sendgrid.sender-name=RealLive",
                    "notification.email.sendgrid.webhook-verification-public-key=test-public-key",
                    "notification.email.sendgrid.email-verification-template-id=d-test-template");

    @Test
    void bindsLocalTopicAndRetryDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            EmailDeliveryProperties properties = context.getBean(EmailDeliveryProperties.class);
            assertThat(properties.getPartitions()).isEqualTo(3);
            assertThat(properties.getReplicas()).isEqualTo((short) 1);
            assertThat(properties.getMaxRetries()).isEqualTo(2);
            assertThat(properties.getRetentionMs()).isEqualTo(600000);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"notification.email.topic=", "notification.email.dead-letter-topic=email-requests",
            "notification.email.partitions=0", "notification.email.replicas=0", "notification.email.retention-ms=0",
            "notification.email.max-retries=-1", "notification.email.retry-backoff-ms=0",
            "notification.email.sendgrid.api-key=", "notification.email.sendgrid.sender-email=invalid",
            "notification.email.sendgrid.webhook-verification-public-key=",
            "notification.email.sendgrid.email-verification-template-id=",
            "notification.email.sendgrid.email-verification-template-id=wrong-template",
            "notification.email.sendgrid.request-timeout=0s"})
    void rejectsInvalidConfiguration(String property) {
        contextRunner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void requiresBrokerAddressesAndConsumerGroup() {
        KafkaProperties properties = new KafkaProperties();
        assertThatThrownBy(() -> new EmailKafkaConfig(properties))
                .hasMessage("EMAIL_VERIFICATION_CONSUMER_GROUP must be configured");
        properties.getConsumer().setGroupId("test-group");
        properties.setBootstrapServers(List.of());
        assertThatThrownBy(() -> new EmailKafkaConfig(properties))
                .hasMessage("KAFKA_BOOTSTRAP_SERVERS must be configured");
        properties.setBootstrapServers(List.of(" "));
        assertThatThrownBy(() -> new EmailKafkaConfig(properties))
                .hasMessage("KAFKA_BOOTSTRAP_SERVERS must be configured");
        properties.setBootstrapServers(List.of("localhost:9092"));
        assertThatCode(() -> new EmailKafkaConfig(properties)).doesNotThrowAnyException();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmailDeliveryProperties.class)
    static class PropertiesConfiguration {
    }
}
