package com.reallive.paymentservice.donation.dto.response;

import java.util.UUID;

public record VirtualGiftResponse(
        UUID id,
        String name,
        String imageUrl,
        long coinPrice
) {}
