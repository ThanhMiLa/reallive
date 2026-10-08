package com.reallive.userservice.user.dto.request;

import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateProfileRequest {

    @Size(min = 1, max = 100, message = "DISPLAY_NAME_INVALID")
    @Pattern(regexp = "(?sU).*\\S.*", message = "DISPLAY_NAME_INVALID")
    String displayName;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PastOrPresent(message = "DOB_INVALID")
    LocalDate dob;

    MultipartFile avatar;
}
