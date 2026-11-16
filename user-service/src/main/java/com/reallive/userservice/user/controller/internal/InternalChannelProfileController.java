package com.reallive.userservice.user.controller.internal;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.user.dto.request.UpdateProfileRequest;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.service.ProfileService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/api/users")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalChannelProfileController {
    ProfileService profiles;

    @InitBinder
    public void configureBinding(WebDataBinder binder) {
        binder.setAllowedFields("displayName", "avatar");
        binder.registerCustomEditor(String.class, "displayName", new StringTrimmerEditor(false));
    }

    @PatchMapping(value = "/{userId}/channel-profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserProfileResponse> update(
            @PathVariable UUID userId, @Valid @ModelAttribute UpdateProfileRequest request) {
        return ApiResponse.<UserProfileResponse>builder().status(200).code(1000)
                .message("Profile updated successfully").result(profiles.updateFromChannel(userId, request)).build();
    }
}
