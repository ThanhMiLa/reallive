package com.reallive.channelservice.channel.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.validator.constraints.URL;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateChannelRequest {

    @NotNull
    @Size(max = 1000)
    String description;

    @NotNull
    @Size(max = 500)
    @URL(regexp = "(?i)^(https?://.+)?$")
    String bannerUrl;

    @NotEmpty
    List<@NotNull UUID> categoryIds;
}
