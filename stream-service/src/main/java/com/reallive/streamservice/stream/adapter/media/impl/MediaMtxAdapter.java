package com.reallive.streamservice.stream.adapter.media.impl;

import com.reallive.streamservice.common.configuration.MediaControlProperties;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPage;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.adapter.media.dto.MediaServerInfo;
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

import java.time.Instant;
import java.time.format.DateTimeParseException;
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
    ObjectMapper mapper;

    @Override
    public MediaServerInfo getServerInfo() {
        var response = request(HttpMethod.GET, paths().getInfo(), null);
        requireSuccess(response);
        JsonNode body = response.body();
        String version = body.path("version").isString() ? body.path("version").asString() : "";
        String started = body.path("started").isString() ? body.path("started").asString() : "";
        if (started.isBlank()) {
            throw unavailable();
        }
        return new MediaServerInfo(version, started);
    }

    @Override
    public Optional<MediaPathConfiguration> getIngestPath(UUID ingestKeyId) {
        return getPathByName(ingestPath(ingestKeyId));
    }

    @Override
    public void ensureIngestPath(UUID ingestKeyId) {
        String name = ingestPath(ingestKeyId);
        var existing = getPathByName(name);
        if (existing.isPresent()) {
            requirePublisher(existing.get());
            return;
        }
        var payload =
                Map.of(
                        "source", "publisher",
                        "overridePublisher", false,
                        "runOnRead", "",
                        "runOnUnread", "");
        var response = request(HttpMethod.POST, paths().getConfigAdd() + name, payload);
        if (response.status() == 400
                && "path already exists".equals(response.body().path("error").asString())) {
            requirePublisher(getPathByName(name).orElseThrow(this::unavailable));
            return;
        }
        requireSuccess(response);
        requirePublisher(getPathByName(name).orElseThrow(this::unavailable));
    }

    @Override
    public void deleteIngestPath(UUID ingestKeyId) {
        deletePathByName(ingestPath(ingestKeyId));
    }

    @Override
    public Optional<MediaPathStatus> getIngestSource(UUID ingestKeyId) {
        return getSourceByName(ingestPath(ingestKeyId));
    }

    @Override
    public Optional<MediaPathConfiguration> getPlaybackPath(UUID sessionId) {
        return getPathByName(playbackPath(sessionId));
    }

    @Override
    public void ensurePlaybackPath(UUID sessionId, UUID ingestKeyId, String relayReadJwt) {
        if (relayReadJwt == null || relayReadJwt.isBlank()) {
            throw new IllegalArgumentException("Relay read JWT must not be blank");
        }
        String name = playbackPath(sessionId);
        String expectedSourceUrl = relaySourceUrl(ingestKeyId, relayReadJwt);
        var existing = getPathByName(name);
        if (existing.isPresent()) {
            requirePlaybackRelay(existing.get(), expectedSourceUrl);
            return;
        }
        var payload =
                Map.of(
                        "source",
                        expectedSourceUrl,
                        "sourceOnDemand",
                        false,
                        "runOnOnline",
                        "",
                        "runOnOffline",
                        "");
        var response = request(HttpMethod.POST, paths().getConfigAdd() + name, payload);
        if (response.status() == 400
                && "path already exists".equals(response.body().path("error").asString())) {
            requirePlaybackRelay(
                    getPathByName(name).orElseThrow(this::unavailable), expectedSourceUrl);
            return;
        }
        requireSuccess(response);
        requirePlaybackRelay(getPathByName(name).orElseThrow(this::unavailable), expectedSourceUrl);
    }

    @Override
    public void deletePlaybackPath(UUID sessionId) {
        deletePathByName(playbackPath(sessionId));
    }

    @Override
    public Optional<MediaPathStatus> getPlaybackSource(UUID sessionId) {
        return getSourceByName(playbackPath(sessionId));
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
        if (!sourceType.isPublisher()) {
            throw new IllegalArgumentException("Only publisher connections can be kicked");
        }
        String protocol = sourceType == MediaSourceType.RTMP_CONN ? "rtmp" : "rtmps";
        String template = paths().getConnsKick();
        String endpoint =
                template.contains("{protocol}")
                        ? template.replace("{protocol}", protocol)
                        : String.format(template, protocol);
        var response = request(HttpMethod.POST, endpoint + connectionId, null);
        if (!missing(response, "connection not found")) {
            requireSuccess(response);
        }
    }

    private Optional<MediaPathConfiguration> getPathByName(String name) {
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

    private void deletePathByName(String name) {
        var response = request(HttpMethod.DELETE, paths().getConfigDelete() + name, null);
        if (!missing(response, "path not found")) {
            requireSuccess(response);
        }
    }

    private Optional<MediaPathStatus> getSourceByName(String name) {
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

    private <T> MediaPage<T> page(
            String endpoint, int page, int size, Function<JsonNode, T> decode) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid media pagination");
        }
        var response =
                request(HttpMethod.GET, endpoint + "?page=" + page + "&itemsPerPage=" + size, null);
        requireSuccess(response);
        JsonNode body = response.body();
        if (!body.path("items").isArray()
                || !body.path("itemCount").canConvertToInt()
                || !body.path("pageCount").canConvertToInt()
                || body.path("itemCount").asInt() < 0
                || body.path("pageCount").asInt() < 0
                || body.path("items").size() > size) {
            throw unavailable();
        }
        List<T> items = new ArrayList<>();
        body.path("items").forEach(item -> items.add(decode.apply(item)));
        return new MediaPage<>(
                body.path("itemCount").asInt(), body.path("pageCount").asInt(), List.copyOf(items));
    }

    private MediaPathConfiguration configuration(JsonNode body) {
        if (!body.path("name").isString()
                || !body.path("source").isString()
                || !body.path("overridePublisher").isBoolean()) {
            throw unavailable();
        }
        boolean sourceOnDemand =
                body.path("sourceOnDemand").isBoolean() && body.path("sourceOnDemand").asBoolean();
        String runOnOnline =
                body.path("runOnOnline").isString() ? body.path("runOnOnline").asString() : null;
        String runOnOffline =
                body.path("runOnOffline").isString() ? body.path("runOnOffline").asString() : null;
        return new MediaPathConfiguration(
                body.path("name").asString(),
                body.path("source").asString(),
                body.path("overridePublisher").asBoolean(),
                sourceOnDemand,
                runOnOnline,
                runOnOffline);
    }

    private MediaPathStatus source(JsonNode body) {
        if (!body.path("name").isString()
                || !body.path("online").isBoolean()
                || !body.has("source")) {
            throw unavailable();
        }
        Instant onlineTime = parseInstant(body.path("onlineTime"));
        JsonNode source = body.path("source");
        if (source.isNull() && !body.path("online").asBoolean()) {
            return new MediaPathStatus(body.path("name").asString(), false, null, null, null);
        }
        if (!source.isObject() || !source.path("type").isString()) {
            throw unavailable();
        }
        MediaSourceType type = MediaSourceType.fromValue(source.path("type").asString());
        if (type == null) {
            throw unavailable();
        }
        UUID id = null;
        if (type.isPublisher()) {
            if (!source.path("id").isString()) {
                throw unavailable();
            }
            try {
                id = UUID.fromString(source.path("id").asString());
            } catch (IllegalArgumentException exception) {
                throw unavailable();
            }
        }
        return new MediaPathStatus(
                body.path("name").asString(),
                body.path("online").asBoolean(),
                id,
                type,
                onlineTime);
    }

    private Instant parseInstant(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String raw = node.asString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private ControlResponse request(HttpMethod method, String endpoint, Object body) {
        try {
            var request =
                    mediaControlWebClient
                            .method(method)
                            .uri(endpoint);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            }
            return request.exchangeToMono(
                            response ->
                                    response.bodyToMono(JsonNode.class)
                                            .defaultIfEmpty(mapper.createObjectNode())
                                            .map(
                                                    json ->
                                                            new ControlResponse(
                                                                    response.statusCode().value(),
                                                                    json)))
                    .switchIfEmpty(Mono.error(unavailable()))
                    .block(properties.getRequestTimeout());
        } catch (RuntimeException exception) {
            // Do not retain remote request/response exceptions: they may include the API or relay
            // credentials.
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

    private void requirePlaybackRelay(
            MediaPathConfiguration configuration, String expectedSourceUrl) {
        if (!configuration.acceptsPlaybackRelay(expectedSourceUrl)) {
            throw unavailable();
        }
    }

    private String ingestPath(UUID ingestKeyId) {
        return paths().getIngestPrefix() + Objects.requireNonNull(ingestKeyId);
    }

    private String playbackPath(UUID sessionId) {
        return paths().getStreamPrefix() + Objects.requireNonNull(sessionId);
    }

    private String relaySourceUrl(UUID ingestKeyId, String relayReadJwt) {
        String base =
                properties != null && properties.getInternalRtmpBaseUrl() != null
                        ? properties.getInternalRtmpBaseUrl()
                        : "rtmp://127.0.0.1:1935";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + ingestPath(ingestKeyId) + "?token=" + relayReadJwt;
    }

    private MediaControlProperties.Paths paths() {
        return properties != null && properties.getPaths() != null
                ? properties.getPaths()
                : new MediaControlProperties.Paths();
    }

    private AppException unavailable() {
        return new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    }

    private record ControlResponse(
        int status,
        JsonNode body
) {}
}
