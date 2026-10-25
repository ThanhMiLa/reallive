package com.reallive.streamservice.stream.mapper;

import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class StreamSessionMapper {

    public StreamSessionSnapshot toSnapshot(StreamSessionEntity entity) {
        return StreamSessionSnapshot.fromEntity(entity);
    }
}
