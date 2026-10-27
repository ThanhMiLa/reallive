package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.common.configuration.MediaControlProperties;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaMtxAdapter implements MediaServerAdapter {

    WebClient mediaControlWebClient;
    MediaControlProperties properties;
    MediaJwtIssuer issuer;
    ObjectMapper mapper;

    @Override
    public Optional<MediaPathConfiguration> getPath(UUID sessionId) {
        String name = path(sessionId);
        var response = request(HttpMethod.GET, paths().getConfigGet() + name, null);
        if (missing(response, "path configuration not found")) {
            return Optional.empty();
        }
        requireSuccess(response);
        var configuration = configuration(response.body());
        if (!name.equals(configuration.name())) {
            throw unavailable();
        }
        return Optional.of(configuration);
    }

    @Override
    public void ensurePath(UUID sessionId) {
        var existing = getPath(sessionId);
        if (existing.isPresent()) {
            requirePublisher(existing.get());
            return;
        }
        var response = request(HttpMethod.POST, paths().getConfigAdd() + path(sessionId),
                Map.of("source", "publisher", "overridePublisher", false));
        if (response.status() == 400 && "path already exists".equals(response.body().path("error").asString())) {
            requirePublisher(getPath(sessionId).orElseThrow(this::unavailable));
            return;
        }
        requireSuccess(response);
        requirePublisher(getPath(sessionId).orElseThrow(this::unavailable));
    }

    @Override
    public void deletePath(UUID sessionId) {
        var response = request(HttpMethod.DELETE, paths().getConfigDelete() + path(sessionId), null);
        if (!missing(response, "path not found")) {
            requireSuccess(response);
        }
    }

    @Override
    public Optional<MediaPathStatus> getSource(UUID sessionId) {
        String name = path(sessionId);
        var response = request(HttpMethod.GET, paths().getStatusGet() + name, null);
        if (missing(response, "path not found")) {
            return Optional.empty();
        }
        requireSuccess(response);
        var source = source(response.body());
        if (!name.equals(source.name())) {
            throw unavailable();
        }
        return Optional.of(source);
    }

    @Override
    public MediaPage<MediaPathConfiguration> listConfigurations(int page, int size) {
        return page(paths().getConfigList(), page, size, this::configuration);
    }

    @Override
    public MediaPage<MediaPathStatus> listPaths(int page, int size) {
        return page(paths().getStatusList(), page, size, this::source);
    }

    @Override
    public void kick(MediaSourceType sourceType, UUID connectionId) {
        Objects.requireNonNull(sourceType);
        Objects.requireNonNull(connectionId);
        String protocol = sourceType == MediaSourceType.RTMP_CONN ? "rtmp" : "rtmps";
        String template = paths().getConnsKick();
        String endpoint = template.contains("{protocol}")
                ? template.replace("{protocol}", protocol)
                : String.format(template, protocol);
        var response = request(HttpMethod.POST, endpoint + connectionId, null);
        if (!missing(response, "connection not found")) {
            requireSuccess(response);
        }
    }

    private <T> MediaPage<T> page(String endpoint, int page, int size, Function<JsonNode, T> decode) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid media pagination");
        }
        var response = request(HttpMethod.GET, endpoint + "?page=" + page + "&itemsPerPage=" + size, null);
        requireSuccess(response);
        JsonNode body = response.body();
        if (!body.path("items").isArray() || !body.path("itemCount").canConvertToInt()
                || !body.path("pageCount").canConvertToInt() || body.path("itemCount").asInt() < 0
                || body.path("pageCount").asInt() < 0 || body.path("items").size() > size) {
            throw unavailable();
        }
        List<T> items = new ArrayList<>();
        body.path("items").forEach(item -> items.add(decode.apply(item)));
        return new MediaPage<>(body.path("itemCount").asInt(), body.path("pageCount").asInt(), List.copyOf(items));
    }

    private MediaPathConfiguration configuration(JsonNode body) {
        if (!body.path("name").isString() || !body.path("source").isString()
                || !body.path("overridePublisher").isBoolean()) {
            throw unavailable();
        }
        return new MediaPathConfiguration(body.path("name").asString(), body.path("source").asString(),
                body.path("overridePublisher").asBoolean());
    }

    private MediaPathStatus source(JsonNode body) {
        if (!body.path("name").isString() || !body.path("online").isBoolean() || !body.has("source")) {
            throw unavailable();
        }
        JsonNode source = body.path("source");
        if (source.isNull() && !body.path("online").asBoolean()) {
            return new MediaPathStatus(body.path("name").asString(), false, null, null);
        }
        if (!source.isObject() || !source.path("type").isString() || !source.path("id").isString()) {
            throw unavailable();
        }
        try {
            MediaSourceType type = MediaSourceType.fromValue(source.path("type").asString());
            UUID id = UUID.fromString(source.path("id").asString());
            if (type == null) {
                throw unavailable();
            }
            return new MediaPathStatus(body.path("name").asString(), body.path("online").asBoolean(), id, type);
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private ControlResponse request(HttpMethod method, String endpoint, Object body) {
        try {
            var request = mediaControlWebClient.method(method).uri(endpoint)
                    .headers(headers -> headers.setBearerAuth(issuer.issueControlApi().value()));
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            }
            return request.exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                    .defaultIfEmpty(mapper.createObjectNode())
                    .map(json -> new ControlResponse(response.statusCode().value(), json)))
                    .switchIfEmpty(Mono.error(unavailable())).block(properties.getRequestTimeout());
        } catch (RuntimeException exception) {
            // Do not retain remote request/response exceptions: they may include the API credential.
            throw unavailable();
        }
    }

    private boolean missing(ControlResponse response, String error) {
        return response.status() == 404 && error.equals(response.body().path("error").asString());
    }

    private void requireSuccess(ControlResponse response) {
        if (response.status() != 200) {
            throw unavailable();
        }
    }

    private void requirePublisher(MediaPathConfiguration configuration) {
        if (!configuration.acceptsPublisher()) {
            throw unavailable();
        }
    }

    private String path(UUID sessionId) {
        return paths().getStreamPrefix() + Objects.requireNonNull(sessionId);
    }

    private MediaControlProperties.Paths paths() {
        return properties != null && properties.getPaths() != null ? properties.getPaths() : new MediaControlProperties.Paths();
    }

    private AppException unavailable() {
        return new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    }

    private record ControlResponse(
            int status,
            JsonNode body
    ) {}
}
