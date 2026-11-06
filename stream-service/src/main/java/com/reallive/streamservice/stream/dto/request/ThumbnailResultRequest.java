package com.reallive.streamservice.stream.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ThumbnailResultRequest(
        @NotNull
        UUID jobId,

        @NotNull
        UUID sessionId,

        @NotBlank
        @Size(max = 500)
        String objectKey,

        @NotNull
        @Pattern(regexp = "image/jpeg")
        String contentType,

        @Positive
        long sizeBytes,

        @Positive
        int width,

        @Positive
        int height
) {
}
