package com.reallive.streamservice.stream.dto;

import com.reallive.streamservice.stream.dto.request.CreateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.request.UpdateStreamSessionRequest;
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
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class StreamContractsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createTrimsTitleAndRejectsMissingOrOversizedInput() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valid = new CreateStreamSessionRequest(UUID.randomUUID(), "  Title  ", UUID.randomUUID());
            assertEquals("Title", valid.title());
            assertTrue(validator.validate(valid).isEmpty());
            assertFalse(validator.validate(new CreateStreamSessionRequest(null, "  ", null)).isEmpty());
            assertFalse(validator.validate(new CreateStreamSessionRequest(UUID.randomUUID(), "x".repeat(256), UUID.randomUUID())).isEmpty());
        }
    }

    @Test
    void patchPreservesAbsentFieldsAndRejectsNullEmptyOrUnknownFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var title = mapper.readValue(mapper.writeValueAsString(Map.of("title", " New title ")), UpdateStreamSessionRequest.class);
            assertEquals("New title", title.getTitle());
            assertTrue(title.isTitleProvided());
            assertFalse(title.isCategoryProvided());
            assertTrue(validator.validate(title).isEmpty());
            var category = mapper.readValue(mapper.writeValueAsString(Map.of("categoryId", UUID.randomUUID())), UpdateStreamSessionRequest.class);
            assertTrue(validator.validate(category).isEmpty());
            for (var input : List.of(Map.of(), Collections.singletonMap("title", null), Map.of("title", " "), Collections.singletonMap("categoryId", null))) {
                assertFalse(validator.validate(mapper.readValue(mapper.writeValueAsString(input), UpdateStreamSessionRequest.class)).isEmpty());
            }
            for (String field : new String[]{"status", "ownerUserId", "titleProvided", "categoryProvided", "validPatch"}) {
                assertThrows(RuntimeException.class, () -> mapper.readValue(mapper.writeValueAsString(Map.of("title", "ok", field, true)), UpdateStreamSessionRequest.class));
            }
        }
    }

    @Test
    void metadataAndOwnerResponsesContainNoCredentialMediaOrThumbnailDetailsAndUseUtc() {
        var entity = StreamSessionEntity.builder().status(StreamSessionStatus.LIVE).mediaPathState(MediaPathState.OPEN)
                .startedAt(LocalDateTime.of(2026, 10, 26, 0, 0))
                .thumbnailUrl("https://cdn.example.com/livestream-thumbnails/session/job.jpg")
                .thumbnailObjectKey("livestream-thumbnails/session/job.jpg")
                .thumbnailUpdatedAt(LocalDateTime.of(2026, 10, 26, 0, 30))
                .thumbnailJobId(UUID.randomUUID())
                .thumbnailJobStatus(ThumbnailJobStatus.SUCCEEDED)
                .thumbnailJobDeadlineAt(LocalDateTime.of(2026, 10, 26, 0, 34))
                .build();
        var sessionMapper = new StreamSessionMapper();
        var metadata = sessionMapper.toMetadataResponse(entity, Instant.parse("2026-10-26T01:00:00Z"));
        var owner = sessionMapper.toOwnerResponse(entity, Instant.parse("2026-10-26T01:00:00Z"));
        assertEquals("2026-10-26T00:00:00Z", metadata.startedAt().toString());
        for (var response : List.of(metadata, owner)) {
            var tree = mapper.valueToTree(response);
            for (String field : new String[]{"streamKey", "readToken", "publishCredentials", "mediaSourceId", "version",
                    "thumbnailUrl", "thumbnailObjectKey", "thumbnailUpdatedAt", "thumbnailJobId",
                    "thumbnailJobStatus", "thumbnailJobDeadlineAt"}) {
                assertFalse(tree.has(field), field + " must not be exposed by the current API");
            }
        }
        assertFalse(mapper.valueToTree(metadata).has("publishReady"));
    }
}
