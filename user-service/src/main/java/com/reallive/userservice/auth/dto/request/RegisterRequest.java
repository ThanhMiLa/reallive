package com.reallive.userservice.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RegisterRequest {
    @NotBlank(message = "USERNAME_INVALID")
    @Size(min = 5, message = "USERNAME_INVALID")
    String username;

    @NotBlank(message = "PASSWORD_INVALID")
    @Size(min = 5, message = "PASSWORD_INVALID")
    String password;

    @NotBlank(message = "PASSWORD_INVALID")
    @Size(min = 5, message = "PASSWORD_CONFIRM_INVALID")
    String confirmPassword;

    @Email(message = "EMAIL_INVALID")
    String email;
}
