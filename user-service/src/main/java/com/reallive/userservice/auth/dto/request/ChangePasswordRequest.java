package com.reallive.userservice.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChangePasswordRequest {

    @NotBlank(message = "CURRENT_PASSWORD_INVALID")
    @Size(min = 5, message = "CURRENT_PASSWORD_INVALID")
    String currentPassword;

    @NotBlank(message = "PASSWORD_INVALID")
    @Size(min = 5, message = "PASSWORD_INVALID")
    String newPassword;

    @NotBlank(message = "PASSWORD_CONFIRM_INVALID")
    @Size(min = 5, message = "PASSWORD_CONFIRM_INVALID")
    String confirmPassword;
}
