package com.reallive.streamservice.common.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaControlProperties.class)
public class WebClientConfig {

    @Bean
    public WebClient mediaControlWebClient(MediaControlProperties properties) {
        try {
            URI uri = URI.create(properties.getBaseUrl());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || !positive(properties.getConnectTimeout()) || !positive(properties.getRequestTimeout())) {
                throw new IllegalArgumentException();
            }
            HttpClient client = HttpClient.create().disableRetry(true).followRedirect(false)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getConnectTimeout().toMillis()))
                    .responseTimeout(properties.getRequestTimeout());
            return WebClient.builder().baseUrl(uri.toString())
                    .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                    .clientConnector(new ReactorClientHttpConnector(client)).build();
        } catch (IllegalArgumentException | NullPointerException | ArithmeticException exception) {
            throw new IllegalStateException("Invalid media Control API URL or timeout configuration");
        }
    }

    private boolean positive(Duration value) {
        return value != null && value.toMillis() > 0 && value.toMillis() <= Integer.MAX_VALUE;
    }
}
