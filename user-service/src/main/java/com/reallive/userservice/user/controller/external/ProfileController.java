package com.reallive.userservice.user.controller.external;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.util.AuthenticationUserId;
import com.reallive.userservice.user.dto.request.UpdateProfileRequest;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.service.ProfileService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.beans.PropertyEditorSupport;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ProfileController {
    ProfileService profileService;

    @InitBinder("updateProfileRequest")
    public void configureProfileBinding(WebDataBinder binder) {
        binder.setAllowedFields("displayName", "dob", "avatar");
        binder.registerCustomEditor(String.class, "displayName", new StringTrimmerEditor(false));
        binder.registerCustomEditor(LocalDate.class, "dob", new PropertyEditorSupport() {
            @Override
            public void setAsText(String value) {
                if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000-")) {
                    throw new IllegalArgumentException("Date of birth must use yyyy-MM-dd");
                }
                setValue(LocalDate.parse(value));
            }
        });
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        UserProfileResponse profile = profileService.getMyProfile(AuthenticationUserId.getAuthenticatedUserId(jwt));
        return ResponseEntity.ok(ApiResponse.<UserProfileResponse>builder()
                .status(200)
                .code(1000)
                .message("User profile retrieved successfully")
                .result(profile)
                .build());
    }

    @PatchMapping(value = "/me", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @AuthenticationPrincipal Jwt jwt, @Valid @ModelAttribute UpdateProfileRequest request) {
        UserProfileResponse profile = profileService.updateMyProfile(AuthenticationUserId.getAuthenticatedUserId(jwt), request);
        return ResponseEntity.ok(ApiResponse.<UserProfileResponse>builder()
                .status(200)
                .code(1000)
                .message("User profile updated successfully")
                .result(profile)
                .build());
    }
}
