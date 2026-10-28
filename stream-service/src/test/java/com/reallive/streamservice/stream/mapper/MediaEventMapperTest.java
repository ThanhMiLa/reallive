package com.reallive.streamservice.stream.mapper;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
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
    void extractStreamIdParsesValidPath() {
        UUID id = UUID.randomUUID();
        assertEquals(id, mapper.extractStreamId("live/" + id));
    }

    @Test
    void extractStreamIdRejectsInvalidNamespaceOrMalformedUuid() {
        assertThrows(AppException.class, () -> mapper.extractStreamId(null));
        assertThrows(AppException.class, () -> mapper.extractStreamId("other/" + UUID.randomUUID()));
        assertThrows(AppException.class, () -> mapper.extractStreamId("live/not-a-uuid"));
    }

    @Test
    void mapsOnlineCommandCorrectly() {
        UUID eventId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-27T10:15:30.000Z");

        var request = new MediaEventRequest(eventId, "ONLINE", "live/" + streamId, sourceId, "rtmpConn", now);
        var command = mapper.toOnlineCommand(request);

        assertEquals(streamId, command.streamId());
        assertEquals(eventId, command.mediaEventId());
        assertEquals(sourceId, command.mediaSourceId());
        assertEquals(MediaSourceType.RTMP_CONN, command.mediaSourceType());
        assertEquals(now.atZone(ZoneOffset.UTC).toLocalDateTime(), command.occurredAt());
    }

    @Test
    void mapsOfflineCommandCorrectly() {
        UUID eventId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-27T10:20:00.000Z");

        var request = new MediaEventRequest(eventId, "OFFLINE", "live/" + streamId, sourceId, "rtmpsConn", now);
        var command = mapper.toOfflineCommand(request);

        assertEquals(streamId, command.streamId());
        assertEquals(eventId, command.mediaEventId());
        assertEquals(sourceId, command.mediaSourceId());
        assertEquals(MediaSourceType.RTMPS_CONN, command.mediaSourceType());
        assertEquals(now.atZone(ZoneOffset.UTC).toLocalDateTime(), command.occurredAt());
    }

    @Test
    void mapsResponseCorrectly() {
        UUID streamId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        var response = mapper.toResponse(streamId, eventId, MediaEventOutcome.APPLIED, StreamSessionStatus.LIVE);

        assertEquals(streamId, response.streamId());
        assertEquals(eventId, response.eventId());
        assertEquals(MediaEventOutcome.APPLIED, response.outcome());
        assertEquals(StreamSessionStatus.LIVE, response.status());
    }
}
