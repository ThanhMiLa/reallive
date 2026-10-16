package com.reallive.channelservice.channel.dto.request;

import com.reallive.channelservice.channel.dto.validation.HttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CreateChannelRequest {

    @NotNull
    UUID streamerId;

    @NotNull
    UUID userId;

    String name;

    @Size(max = 500)
    @HttpUrl
    String avatarUrl;

    /**
     * Returns the normalized name used for validation and persistence.
     */
    @NotBlank
    @Size(max = 150)
    public String getName() {
        return name == null ? null : name.strip();
    }
}
