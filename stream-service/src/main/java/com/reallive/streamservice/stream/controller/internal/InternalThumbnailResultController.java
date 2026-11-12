package com.reallive.streamservice.stream.controller.internal;

import com.reallive.streamservice.stream.dto.request.ThumbnailResultRequest;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultResponse;
import com.reallive.streamservice.stream.service.ThumbnailResultService;
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
@RequestMapping("/internal/api/thumbnail-results")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "stream.thumbnail", name = "callback-enabled", havingValue = "true")
public class InternalThumbnailResultController {

    ThumbnailResultService resultService;

    @PostMapping
    public ResponseEntity<ThumbnailResultResponse> receive(@Valid @RequestBody ThumbnailResultRequest request) {
        return ResponseEntity.ok(resultService.apply(request));
    }
}
