package com.reallive.streamservice.stream.mapper;

import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StreamSessionMapperTest {

    private final StreamSessionMapper mapper = new StreamSessionMapper();

    @Test
    void snapshotPreservesThumbnailImageAndJobState() {
        UUID sessionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        LocalDateTime updatedAt = LocalDateTime.of(2026, 11, 5, 2, 30);
        LocalDateTime deadline = updatedAt.plusMinutes(4);
        String objectKey = "livestream-thumbnails/" + sessionId + "/" + jobId + ".jpg";
        StreamSessionEntity entity = StreamSessionEntity.builder()
                .id(sessionId)
                .status(StreamSessionStatus.LIVE)
                .mediaPathState(MediaPathState.OPEN)
                .startedAt(updatedAt.minusMinutes(10))
                .thumbnailUrl("https://cdn.example.com/" + objectKey)
                .thumbnailObjectKey(objectKey)
                .thumbnailUpdatedAt(updatedAt)
                .thumbnailJobId(jobId)
                .thumbnailJobStatus(ThumbnailJobStatus.SUCCEEDED)
                .thumbnailJobDeadlineAt(deadline)
                .build();

        StreamSessionSnapshot snapshot = mapper.toSnapshot(entity);

        assertEquals(sessionId, snapshot.id());
        assertEquals("https://cdn.example.com/" + objectKey, snapshot.thumbnailUrl());
        assertEquals(objectKey, snapshot.thumbnailObjectKey());
        assertEquals(updatedAt, snapshot.thumbnailUpdatedAt());
        assertEquals(jobId, snapshot.thumbnailJobId());
        assertEquals(ThumbnailJobStatus.SUCCEEDED, snapshot.thumbnailJobStatus());
        assertEquals(deadline, snapshot.thumbnailJobDeadlineAt());
    }

    @Test
    void snapshotPreservesNullThumbnailStateBeforeTheFirstJob() {
        StreamSessionEntity entity = StreamSessionEntity.builder()
                .status(StreamSessionStatus.LIVE)
                .mediaPathState(MediaPathState.OPEN)
                .build();

        StreamSessionSnapshot snapshot = mapper.toSnapshot(entity);

        assertNull(snapshot.thumbnailUrl());
        assertNull(snapshot.thumbnailObjectKey());
        assertNull(snapshot.thumbnailUpdatedAt());
        assertNull(snapshot.thumbnailJobId());
        assertNull(snapshot.thumbnailJobStatus());
        assertNull(snapshot.thumbnailJobDeadlineAt());
    }
}
