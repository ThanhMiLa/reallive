package com.reallive.streamservice.stream.controller.media;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.stream.dto.response.MediaRecoveryResponse;
import com.reallive.streamservice.stream.service.StreamIngestRecoveryService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/internal/api/media/restore-ingest-paths")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaRecoveryController {

    StreamIngestRecoveryService recovery;

    @PostMapping
    public ResponseEntity<ApiResponse<MediaRecoveryResponse>> restoreIngestPaths() {
        int restored = recovery.restoreIngestPaths();
        log.info("Restored MediaMTX ingest paths for {} active channels", restored);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.<MediaRecoveryResponse>builder()
                        .status(200)
                        .code(1000)
                        .message("Media ingest paths restored")
                        .result(new MediaRecoveryResponse(restored))
                        .timestamp(Instant.now().toString())
                        .build());
    }
}
