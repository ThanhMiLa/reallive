package com.reallive.paymentservice.donation.service;

import com.reallive.paymentservice.donation.dto.response.VirtualGiftResponse;

import java.util.List;

public interface VirtualGiftService {

    /**
     * Lists active virtual gifts in ascending coin price and name order.
     *
     * @return the gifts currently available for immediate donation
     */
    List<VirtualGiftResponse> getGifts();
}
