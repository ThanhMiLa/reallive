package com.reallive.streamservice.stream.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SaveStreamSetupRequest(
        @NotBlank(message = "Stream title is required")
        @Size(max = 255, message = "Stream title must not exceed 255 characters")
        String title,

        @NotNull(message = "Category is required")
        UUID categoryId
) {}
