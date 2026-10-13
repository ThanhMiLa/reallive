package com.reallive.channelservice.channel.dto.request;

import com.reallive.channelservice.channel.dto.validation.NotEmptyFile;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateChannelRequest {

    @Size(min = 1, max = 1000)
    @Pattern(regexp = "(?sU).*\\S.*")
    String description;

    @NotEmptyFile
    MultipartFile bannerUrl;

    @Size(min = 1)
    List<@NotNull UUID> categoryIds;
}
