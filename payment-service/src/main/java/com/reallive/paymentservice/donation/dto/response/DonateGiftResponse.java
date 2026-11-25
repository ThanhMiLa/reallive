package com.reallive.paymentservice.donation.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record DonateGiftResponse(
        UUID donationId,
        UUID streamId,
        UUID channelId,
        UUID streamerId,
        UUID giftId,
        String giftName,
        String giftImageUrl,
        long coinPrice,
        long remainingBalance,
        LocalDateTime createdAt
) {}
