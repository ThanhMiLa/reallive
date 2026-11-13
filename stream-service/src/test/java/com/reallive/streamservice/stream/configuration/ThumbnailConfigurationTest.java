package com.reallive.streamservice.stream.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.common.configuration.SchedulingConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

class ThumbnailConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            SchedulingConfiguration.class, ThumbnailConfiguration.class)
                    .withBean(MediaProperties.class, MediaProperties::new);

    @Test
    void disabledThumbnailDoesNotRequireCredentialsOrCreateItsScheduler() {
        runner.withPropertyValues("stream.thumbnail.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasBean("reconnectTaskScheduler");
                            assertThat(context).doesNotHaveBean("mediaOperationTaskScheduler");
                            assertThat(context).doesNotHaveBean("mediaReconciliationTaskScheduler");
                            assertThat(context).doesNotHaveBean("thumbnailTaskScheduler");
                            assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(1);
                        });
    }

    @Test
    void enabledThumbnailRejectsInvalidConfigurationAtStartup() {
        runner.withPropertyValues("stream.thumbnail.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalStateException.class)
                                    .hasStackTraceContaining(
                                            "Invalid thumbnail dispatch configuration");
                        });
    }

    @Test
    void enabledThumbnailWithValidConfigurationCreatesBothSchedulers() {
        runner.withPropertyValues(
                        "stream.thumbnail.enabled=true",
                        "stream.thumbnail.internal-token=test-internal-token-0123456789abcdef")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasBean("reconnectTaskScheduler");
                            assertThat(context).doesNotHaveBean("mediaOperationTaskScheduler");
                            assertThat(context).doesNotHaveBean("mediaReconciliationTaskScheduler");
                            assertThat(context).hasBean("thumbnailTaskScheduler");
                            assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(2);
                        });
    }

    @Test
    void callbackEnabledRejectsMissingCredentialsAtStartup() {
        runner.withPropertyValues("stream.thumbnail.callback-enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalStateException.class)
                                    .hasStackTraceContaining(
                                            "Invalid thumbnail callback configuration");
                        });
    }

    @Test
    void callbackEnabledRejectsAnUnsafePublicBaseUrl() {
        runner.withPropertyValues(
                        "stream.thumbnail.callback-enabled=true",
                        "stream.thumbnail.callback-internal-token=test-internal-token-0123456789abcdef",
                        "stream.thumbnail.public-base-url=https://thumbnails.example.com/?token=secret")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalStateException.class)
                                    .hasStackTraceContaining(
                                            "Invalid thumbnail callback configuration");
                        });
    }

    @Test
    void callbackEnabledWithValidConfigurationDoesNotRequireTheScheduler() {
        runner.withPropertyValues(
                        "stream.thumbnail.enabled=false",
                        "stream.thumbnail.callback-enabled=true",
                        "stream.thumbnail.callback-internal-token=test-internal-token-0123456789abcdef",
                        "stream.thumbnail.public-base-url=https://thumbnails.example.com/base/",
                        "stream.thumbnail.max-jpeg-bytes=5242880")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean("thumbnailTaskScheduler");
                        });
    }
}
