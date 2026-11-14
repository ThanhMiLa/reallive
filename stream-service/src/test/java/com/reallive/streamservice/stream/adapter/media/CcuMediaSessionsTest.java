package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.common.configuration.MediaControlProperties;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.dto.CcuMediaReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CcuMediaSessionsTest {

    private final MediaControlProperties properties = new MediaControlProperties();
    private final List<ClientRequest> requests = new ArrayList<>();
    private final UUID readerId = UUID.randomUUID();
    private final String path = "live/" + UUID.randomUUID();
    private final String user = UUID.randomUUID().toString();

    @Test
    void readsTheViewerIdentityWithoutControlApiCredentials() {
        var media = client(HttpStatus.OK, reader(readerId, user));

        assertThat(media.get(readerId)).contains(new CcuMediaReader(readerId, path, user));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().url().getPath()).isEqualTo("/v3/hls/sessions/get/" + readerId);
        assertThat(requests.getFirst().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isNull();
    }

    @Test
    void onlyAnExplicit404MeansTheReaderHasEnded() {
        assertThat(client(HttpStatus.NOT_FOUND, "{\"error\":\"session not found\"}").get(readerId)).isEmpty();
        assertUnavailable(() -> client(HttpStatus.OK, "").get(readerId));
        assertUnavailable(() -> client(HttpStatus.NO_CONTENT, "").get(readerId));
    }

    @Test
    void malformedOrMismatchedSuccessfulResponsesAreUnavailableRatherThanValidReaders() {
        for (String body : List.of(
                "{}",
                "not-json",
                reader(readerId, user).replace(readerId.toString(), "not-a-uuid"),
                reader(UUID.randomUUID(), user))) {
            assertUnavailable(() -> client(HttpStatus.OK, body).get(readerId));
        }
    }

    @Test
    void listsACompletePageAndPreservesTechnicalIdentitiesForTheSchedulerToFilter() {
        UUID workerReader = UUID.randomUUID();
        var media = client(HttpStatus.OK,
                "{\"items\":[" + reader(readerId, user) + "," + reader(workerReader, "media-worker") + "]}");

        assertThat(media.page(2)).containsExactly(
                new CcuMediaReader(readerId, path, user),
                new CcuMediaReader(workerReader, path, "media-worker"));
        assertThat(requests.getFirst().url().getPath()).isEqualTo("/v3/hls/sessions/list");
        assertThat(requests.getFirst().url().getQuery()).isEqualTo("page=2&itemsPerPage=100");
        assertThat(requests.getFirst().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isNull();
    }

    @Test
    void rejectsMissingOrMalformedPageItemsSoReconciliationCannotTreatThemAsAnEmptySnapshot() {
        for (String body : List.of("{}", "{\"items\":null}", "{\"items\":{}}", "{\"items\":[{}]}")) {
            assertUnavailable(() -> client(HttpStatus.OK, body).page(0));
        }
        assertUnavailable(() -> client(HttpStatus.NOT_FOUND, "{}").page(0));
        assertUnavailable(() -> client(HttpStatus.OK, "").page(0));
        assertUnavailable(() -> client(HttpStatus.NO_CONTENT, "").page(0));
    }

    @Test
    void authenticationAndServerFailuresNeverMeanTheReaderHasEnded() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE)) {
            assertUnavailable(() -> client(status, "{}").get(readerId));
            assertUnavailable(() -> client(status, "{}").page(0));
        }
    }

    @Test
    void transportFailureAndTimeoutAreTranslatedToTheMediaUnavailableError() {
        var failed = WebClient.builder().baseUrl("http://media.test:9997")
                .exchangeFunction(request -> Mono.error(new IllegalStateException("Connection failed"))).build();
        assertUnavailable(() -> new CcuMediaSessions(failed, properties).get(readerId));

        properties.setRequestTimeout(Duration.ofMillis(5));
        var stalled = WebClient.builder().baseUrl("http://media.test:9997")
                .exchangeFunction(request -> Mono.never()).build();
        assertUnavailable(() -> new CcuMediaSessions(stalled, properties).get(readerId));
    }

    private CcuMediaSessions client(HttpStatus status, String body) {
        var webClient = WebClient.builder().baseUrl("http://media.test:9997")
                .exchangeFunction(request -> {
                    requests.add(request);
                    return Mono.just(ClientResponse.create(status)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(body).build());
                }).build();
        return new CcuMediaSessions(webClient, properties);
    }

    private String reader(UUID id, String identity) {
        return """
                {"id":"%s","path":"%s","user":"%s"}
                """.formatted(id, path, identity);
    }

    private void assertUnavailable(Executable request) {
        assertThat(assertThrows(AppException.class, request).getErrorCode())
                .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    }
}
