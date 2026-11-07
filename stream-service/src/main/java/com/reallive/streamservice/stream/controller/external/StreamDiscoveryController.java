package com.reallive.streamservice.stream.controller.external;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.dto.response.StreamDiscoveryResponse;
import com.reallive.streamservice.stream.service.StreamDiscoveryService;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamDiscoveryController {
    StreamDiscoveryService service;

    @GetMapping
    public ResponseEntity<ApiResponse<StreamDiscoveryResponse>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID categoryId) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.<StreamDiscoveryResponse>builder()
                .status(200)
                .code(1000)
                .message("Live streams retrieved")
                .result(service.list(page, size, categoryId))
                .timestamp(Instant.now().toString())
                .build());
    }
}
