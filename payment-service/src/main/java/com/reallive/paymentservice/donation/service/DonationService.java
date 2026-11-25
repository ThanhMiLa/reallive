package com.reallive.paymentservice.donation.service;

import com.reallive.paymentservice.donation.dto.request.DonateGiftRequest;
import com.reallive.paymentservice.donation.dto.response.DonateGiftResponse;

import java.util.UUID;

public interface DonationService {

    /**
     * Atomically buys one gift and credits its full coin price to the live stream's owner.
     * Replaying the same donor key and payload returns the existing donation and current balance.
     *
     * @param donorUserId authenticated donor identifier
     * @param request target stream, gift and donor-scoped idempotency key
     * @return the completed donation, gift details and remaining donor balance
     * @throws com.reallive.paymentservice.common.exception.AppException if eligibility, funds or key validation fails
     */
    DonateGiftResponse donate(UUID donorUserId, DonateGiftRequest request);
}
