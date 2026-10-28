package com.reallive.streamservice.common.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "stream.lifecycle.reconnect-sweep-enabled", matchIfMissing = true)
public class SchedulingConfiguration {
}
