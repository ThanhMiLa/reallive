package com.reallive.streamservice.stream.configuration;

import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.common.configuration.SchedulingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, ThumbnailConfiguration.class)
            .withBean(MediaProperties.class, MediaProperties::new);

    @Test
    void disabledThumbnailDoesNotRequireCredentialsOrCreateItsScheduler() {
        runner.withPropertyValues("stream.thumbnail.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("reconnectTaskScheduler");
            assertThat(context).doesNotHaveBean("thumbnailTaskScheduler");
            assertThat(context).hasSingleBean(TaskScheduler.class);
        });
    }

    @Test
    void enabledThumbnailRejectsInvalidConfigurationAtStartup() {
        runner.withPropertyValues("stream.thumbnail.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Invalid thumbnail dispatch configuration");
        });
    }

    @Test
    void enabledThumbnailWithValidConfigurationCreatesBothSchedulers() {
        runner.withPropertyValues(
                "stream.thumbnail.enabled=true",
                "stream.thumbnail.internal-token=test-internal-token-0123456789abcdef"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("reconnectTaskScheduler");
            assertThat(context).hasBean("thumbnailTaskScheduler");
            assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(2);
        });
    }
}
