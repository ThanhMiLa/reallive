package com.reallive.paymentservice.donation.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DonateGiftRequest(
        @NotNull UUID streamId,
        @NotNull UUID giftId,
        @NotNull UUID idempotencyKey
) {}
