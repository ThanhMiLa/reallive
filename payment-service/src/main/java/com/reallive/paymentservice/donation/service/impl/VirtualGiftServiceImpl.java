package com.reallive.paymentservice.donation.service.impl;

import com.reallive.paymentservice.donation.dto.response.VirtualGiftResponse;
import com.reallive.paymentservice.donation.entity.VirtualGiftEntity;
import com.reallive.paymentservice.donation.repository.VirtualGiftRepository;
import com.reallive.paymentservice.donation.service.VirtualGiftService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class VirtualGiftServiceImpl implements VirtualGiftService {

    VirtualGiftRepository giftRepository;

    @Override
    @Transactional(readOnly = true)
    public List<VirtualGiftResponse> getGifts() {
        return giftRepository.findAllByStatusOrderByCoinPriceAscNameAsc(VirtualGiftEntity.Status.ACTIVE).stream()
                .map(gift -> new VirtualGiftResponse(gift.getId(), gift.getName(), gift.getImageUrl(), gift.getCoinPrice()))
                .toList();
    }
}
