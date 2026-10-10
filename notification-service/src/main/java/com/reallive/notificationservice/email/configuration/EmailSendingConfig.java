package com.reallive.notificationservice.email.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class EmailSendingConfig {

    @Bean
    Clock emailClock() {
        return Clock.systemUTC();
    }

    @Bean
    public WebClient sendGridWebClient(EmailDeliveryProperties properties) {
        var settings = properties.getSendgrid();
        var httpClient = HttpClient.newBuilder().connectTimeout(settings.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return WebClient.builder().baseUrl(settings.getBaseUrl())
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32768)).build();
    }
}
