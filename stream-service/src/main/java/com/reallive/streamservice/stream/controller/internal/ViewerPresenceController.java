package com.reallive.streamservice.stream.controller.internal;

import com.reallive.streamservice.stream.dto.request.ViewerPresenceEvent;
import io.micrometer.core.instrument.Metrics;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/api/viewer-events")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "stream.ccu", name = "enabled", havingValue = "true")
public class ViewerPresenceController {
    StreamViewerPresenceService service;

    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody ViewerPresenceEvent event) {
        try {
            service.accept(event);
        } catch (RuntimeException exception) {
            Metrics.counter("ccu.callback.failures").increment();
            throw exception;
        }
        return ResponseEntity.ok().build();
    }
}
