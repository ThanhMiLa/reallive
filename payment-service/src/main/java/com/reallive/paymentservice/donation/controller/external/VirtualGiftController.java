package com.reallive.paymentservice.donation.controller.external;

import com.reallive.paymentservice.donation.dto.response.VirtualGiftResponse;
import com.reallive.paymentservice.donation.service.VirtualGiftService;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import com.reallive.paymentservice.common.dto.ApiResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/gifts")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class VirtualGiftController {

    VirtualGiftService giftService;

    @GetMapping
    public ApiResponse<List<VirtualGiftResponse>> getGifts() {
        var result = giftService.getGifts();
        return ApiResponse.<List<VirtualGiftResponse>>builder()
                .status(200)
                .code(0)
                .message("Virtual gifts retrieved successfully")
                .result(result)
                .timestamp(Instant.now().toString())
                .build();
    }
}
