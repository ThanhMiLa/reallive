package com.reallive.streamservice.common.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {

    @Bean(name = "reconnectTaskScheduler")
    ThreadPoolTaskScheduler reconnectTaskScheduler() {
        return createScheduler("reconnect-scheduler-");
    }

    @Bean(name = "thumbnailTaskScheduler")
    @ConditionalOnProperty(name = "stream.thumbnail.enabled", havingValue = "true")
    ThreadPoolTaskScheduler thumbnailTaskScheduler() {
        return createScheduler("thumbnail-scheduler-");
    }

    @Bean(name = "ccuTaskScheduler")
    @ConditionalOnProperty(name = "stream.ccu.enabled", havingValue = "true")
    ThreadPoolTaskScheduler ccuTaskScheduler() {
        var scheduler = createScheduler("ccu-scheduler-");
        scheduler.setPoolSize(2);
        return scheduler;
    }

    private ThreadPoolTaskScheduler createScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
