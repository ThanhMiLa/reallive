package com.reallive.userservice.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateCategoryPreferencesRequest {

    @NotEmpty(message = "Category IDs must not be empty")
    @Size(max = 20, message = "Cannot select more than 20 categories")
    List<@NotNull(message = "Category ID must not be null") UUID> categoryIds;
}
