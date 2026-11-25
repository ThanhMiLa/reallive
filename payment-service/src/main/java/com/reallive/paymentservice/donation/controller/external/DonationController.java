package com.reallive.paymentservice.donation.controller.external;

import com.reallive.paymentservice.common.util.AuthenticationUserId;
import com.reallive.paymentservice.donation.dto.request.DonateGiftRequest;
import com.reallive.paymentservice.donation.dto.response.DonateGiftResponse;
import com.reallive.paymentservice.donation.service.DonationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.reallive.paymentservice.common.dto.ApiResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/donations")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DonationController {

    DonationService donationService;

    @PostMapping
    public ApiResponse<DonateGiftResponse> donate(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DonateGiftRequest request) {
        return ApiResponse.<DonateGiftResponse>builder()
                .status(200).code(0).message("Gift donated successfully")
                .result(donationService.donate(AuthenticationUserId.getAuthenticatedUserId(jwt), request))
                .timestamp(Instant.now().toString()).build();
    }
}
