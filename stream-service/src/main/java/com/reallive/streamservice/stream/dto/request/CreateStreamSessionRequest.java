package com.reallive.streamservice.stream.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateStreamSessionRequest(
        @NotNull
        UUID requestId,

        @NotBlank
        @Size(max = 255)
        String title,

        @NotNull
        UUID categoryId
) {
    public CreateStreamSessionRequest {
        title = title == null ? null : title.trim();
    }
}
