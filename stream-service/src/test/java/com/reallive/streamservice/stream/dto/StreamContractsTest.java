package com.reallive.streamservice.stream.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.reallive.streamservice.stream.dto.request.MediaEventRequest;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.dto.response.StreamKeyResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import com.reallive.streamservice.stream.mapper.StreamSessionMapper;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

class StreamContractsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void saveSetupRequiresNonBlankTitleAndNonNullCategory() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valid = new SaveStreamSetupRequest("Live gaming", UUID.randomUUID());
            assertTrue(validator.validate(valid).isEmpty());
            assertFalse(
                    validator
                            .validate(new SaveStreamSetupRequest("   ", UUID.randomUUID()))
                            .isEmpty());
            assertFalse(validator.validate(new SaveStreamSetupRequest("Title", null)).isEmpty());
            assertFalse(
                    validator
                            .validate(
                                    new SaveStreamSetupRequest("x".repeat(256), UUID.randomUUID()))
                            .isEmpty());
        }
    }

    @Test
    void mediaEventRequestRequiresIngestPathAndPublisherSourceType() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valid =
                    new MediaEventRequest(
                            UUID.randomUUID(),
                            "ONLINE",
                            "ingest/" + UUID.randomUUID(),
                            UUID.randomUUID(),
                            "rtmpConn",
                            Instant.now());
            assertTrue(validator.validate(valid).isEmpty());

            var playbackPath =
                    new MediaEventRequest(
                            UUID.randomUUID(),
                            "ONLINE",
                            "live/" + UUID.randomUUID(),
                            UUID.randomUUID(),
                            "rtmpConn",
                            Instant.now());
            assertFalse(validator.validate(playbackPath).isEmpty());

            var relaySource =
                    new MediaEventRequest(
                            UUID.randomUUID(),
                            "ONLINE",
                            "ingest/" + UUID.randomUUID(),
                            UUID.randomUUID(),
                            "rtmpSource",
                            Instant.now());
            assertFalse(validator.validate(relaySource).isEmpty());
        }
    }

    @Test
    void setupResponseRedactsCredentialsInToString() {
        String secretKey = UUID.randomUUID() + "?token=super-secret-jwt-value";
        var response =
                new StreamKeyResponse(UUID.randomUUID(), "rtmp://localhost:1935/ingest", secretKey);
        assertFalse(response.toString().contains("super-secret-jwt-value"));
        assertTrue(response.toString().contains("REDACTED"));
    }

    @Test
    void metadataAndOwnerResponsesContainNoCredentialMediaOrThumbnailDetailsAndUseUtc() {
        var entity =
                StreamSessionEntity.builder()
                        .status(StreamSessionStatus.LIVE)
                        .mediaPathState(MediaPathState.OPEN)
                        .startedAt(LocalDateTime.of(2026, 10, 26, 0, 0))
                        .thumbnailUrl(
                                "https://cdn.example.com/livestream-thumbnails/session/job.jpg")
                        .thumbnailObjectKey("livestream-thumbnails/session/job.jpg")
                        .thumbnailUpdatedAt(LocalDateTime.of(2026, 10, 26, 0, 30))
                        .thumbnailJobId(UUID.randomUUID())
                        .thumbnailJobStatus(ThumbnailJobStatus.SUCCEEDED)
                        .thumbnailJobDeadlineAt(LocalDateTime.of(2026, 10, 26, 0, 34))
                        .build();
        var sessionMapper = new StreamSessionMapper();
        var metadata =
                sessionMapper.toMetadataResponse(entity, Instant.parse("2026-10-26T01:00:00Z"));
        var owner = sessionMapper.toOwnerResponse(entity, Instant.parse("2026-10-26T01:00:00Z"));
        assertEquals("2026-10-26T00:00:00Z", metadata.startedAt().toString());
        for (var response : List.of(metadata, owner)) {
            var tree = mapper.valueToTree(response);
            for (String field :
                    new String[] {
                        "streamKey",
                        "readToken",
                        "publishCredentials",
                        "mediaSourceId",
                        "ingestKeyId",
                        "version",
                        "thumbnailUrl",
                        "thumbnailObjectKey",
                        "thumbnailUpdatedAt",
                        "thumbnailJobId",
                        "thumbnailJobStatus",
                        "thumbnailJobDeadlineAt"
                    }) {
                assertFalse(tree.has(field), field + " must not be exposed by the current API");
            }
        }
        assertFalse(mapper.valueToTree(metadata).has("publishReady"));
    }
}
