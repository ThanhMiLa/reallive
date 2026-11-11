package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.common.configuration.MediaControlProperties;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;


@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CcuMediaSessions {
    WebClient mediaControlWebClient;
    MediaControlProperties properties;
    MediaJwtIssuer issuer;

    public Optional<CcuMediaReader> get(UUID id) {
        var response = request("/v3/hls/sessions/get/" + id);
        if (response == null) return Optional.empty();
        var result = reader(response);
        if (!id.equals(result.id())) throw unavailable();
        return Optional.of(result);
    }

    public List<CcuMediaReader> page(int page) {
        var response = request("/v3/hls/sessions/list?page=" + page + "&itemsPerPage=100");
        if (response == null || !response.path("items").isArray()) throw unavailable();
        var result = new ArrayList<CcuMediaReader>();
        response.path("items").forEach(item -> result.add(reader(item)));
        return result;
    }

    private CcuMediaReader reader(JsonNode node) {
        try {
            if (!node.path("id").isString() || !node.path("path").isString() || !node.path("user").isString()) throw unavailable();
            return new CcuMediaReader(UUID.fromString(node.path("id").asString()),
                    node.path("path").asString(), node.path("user").asString());
        } catch (IllegalArgumentException exception) { throw unavailable(); }
    }

    private JsonNode request(String uri) {
        try {
            return mediaControlWebClient.get().uri(uri)
                    .headers(headers -> headers.setBearerAuth(issuer.issueControlApi().value()))
                    .exchangeToMono(response -> {
                        if (response.statusCode().value() == 404) return response.releaseBody().then(Mono.empty());
                        if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(Mono.error(unavailable()));
                        return response.bodyToMono(JsonNode.class).switchIfEmpty(Mono.error(unavailable()));
                    }).block(properties.getRequestTimeout());
        } catch (RuntimeException exception) {
            Metrics.counter("ccu.media.lookup.failures").increment();
            throw unavailable();
        }
    }

    private AppException unavailable() { return new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE); }
}
