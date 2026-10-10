package com.reallive.notificationservice.email.client.sendgrid;

import com.reallive.notificationservice.email.client.sendgrid.dto.request.SendGridMailRequest;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.function.Function;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SendGridClient {

    WebClient sendGridWebClient;
    EmailDeliveryProperties properties;

    /** Calls Mail Send with the prepared payload; the caller handles the response. */
    public <T> Mono<T> send(SendGridMailRequest request, Function<ClientResponse, Mono<T>> responseHandler) {
        return sendGridWebClient.post()
                .uri("/v3/mail/send")
                .headers(headers -> headers.setBearerAuth(properties.getSendgrid().getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchangeToMono(responseHandler);
    }
}
