package com.reallive.streamservice.stream.mapper;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.request.MediaEventRequest;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MediaEventMapperTest {

    private final MediaEventMapper mapper = new MediaEventMapper();

    @Test
    void extractIngestKeyIdParsesValidIngestPath() {
        UUID id = UUID.randomUUID();
        assertEquals(id, mapper.extractIngestKeyId("ingest/" + id));
    }

    @Test
    void extractIngestKeyIdRejectsPlaybackNamespaceOrMalformedUuid() {
        assertThrows(AppException.class, () -> mapper.extractIngestKeyId(null));
        assertThrows(AppException.class, () -> mapper.extractIngestKeyId("live/" + UUID.randomUUID()));
        assertThrows(AppException.class, () -> mapper.extractIngestKeyId("ingest/not-a-uuid"));
    }

    @Test
    void mapsIngestCommandAndRejectsNonPublisherSourceTypes() {
        UUID eventId = UUID.randomUUID();
        UUID ingestKeyId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-27T10:15:30.000Z");

        var request = new MediaEventRequest(eventId, "ONLINE", "ingest/" + ingestKeyId, sourceId, "rtmpsConn", now);
        var command = mapper.toIngestCommand(request);

        assertEquals(ingestKeyId, command.ingestKeyId());
        assertEquals(eventId, command.mediaEventId());
        assertEquals(sourceId, command.mediaSourceId());
        assertEquals(MediaSourceType.RTMPS_CONN, command.mediaSourceType());
        assertEquals(now.atZone(ZoneOffset.UTC).toLocalDateTime(), command.occurredAt());

        var nonPublisher = new MediaEventRequest(eventId, "ONLINE", "ingest/" + ingestKeyId, sourceId, "rtmpSource", now);
        assertThrows(AppException.class, () -> mapper.toIngestCommand(nonPublisher));
    }

    @Test
    void mapsNullableAndActiveResponsesCorrectly() {
        UUID streamId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        var accepted = mapper.toResponse(streamId, eventId, MediaEventOutcome.ACCEPTED, StreamSessionStatus.LIVE);
        assertEquals(streamId, accepted.streamId());
        assertEquals(eventId, accepted.eventId());
        assertEquals(MediaEventOutcome.ACCEPTED, accepted.outcome());
        assertEquals(StreamSessionStatus.LIVE, accepted.status());

        var pendingSession = mapper.toResponse(null, eventId, MediaEventOutcome.ACCEPTED, null);
        assertNull(pendingSession.streamId());
        assertNull(pendingSession.status());
    }
}
