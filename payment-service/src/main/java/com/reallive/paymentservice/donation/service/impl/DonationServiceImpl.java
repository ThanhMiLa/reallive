package com.reallive.paymentservice.donation.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.client.stream.StreamDonationContextApiClient;
import com.reallive.paymentservice.client.stream.StreamDonationContextResponse;
import com.reallive.paymentservice.donation.dto.request.DonateGiftRequest;
import com.reallive.paymentservice.donation.dto.response.DonateGiftResponse;
import com.reallive.paymentservice.donation.entity.DonationEntity;
import com.reallive.paymentservice.donation.entity.VirtualGiftEntity;
import com.reallive.paymentservice.donation.repository.DonationRepository;
import com.reallive.paymentservice.donation.repository.VirtualGiftRepository;
import com.reallive.paymentservice.donation.service.DonationService;
import com.reallive.paymentservice.wallet.entity.WalletTransactionEntity;
import com.reallive.paymentservice.wallet.repository.StreamerWalletRepository;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import com.reallive.paymentservice.wallet.repository.WalletTransactionRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DonationServiceImpl implements DonationService {

    DonationRepository donationRepository;
    VirtualGiftRepository giftRepository;
    UserWalletRepository userWalletRepository;
    WalletTransactionRepository walletTransactionRepository;
    StreamerWalletRepository streamerWalletRepository;
    StreamDonationContextApiClient streamClient;
    PlatformTransactionManager transactionManager;
    Clock paymentClock;

    @Override
    public DonateGiftResponse donate(UUID donorUserId, DonateGiftRequest request) {
        var existing = donationRepository.findByDonorUserIdAndIdempotencyKey(donorUserId, request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        var context = streamClient.getDonationContext(request.streamId());
        if (!"PUBLIC".equals(context.visibility())) {
            throw new AppException(ErrorCode.STREAM_NOT_ACCESSIBLE);
        }
        if (!"LIVE".equals(context.status())) {
            throw new AppException(ErrorCode.STREAM_NOT_LIVE);
        }
        if (donorUserId.equals(context.ownerUserId())) {
            throw new AppException(ErrorCode.SELF_DONATION_NOT_ALLOWED);
        }

        try {
            return new TransactionTemplate(transactionManager)
                    .execute(status -> completeDonation(donorUserId, request, context));
        } catch (DataIntegrityViolationException exception) {
            // The competing insert has committed by the time the unique constraint fails.
            var completed = donationRepository.findByDonorUserIdAndIdempotencyKey(donorUserId, request.idempotencyKey());
            if (completed.isPresent()) {
                return replay(completed.get(), request);
            }
            log.warn("Donation transaction failed a data integrity constraint without an existing retry");
            throw new AppException(ErrorCode.PAYMENT_DATA_UNAVAILABLE);
        }
    }

    private DonateGiftResponse completeDonation(
            UUID donorUserId, DonateGiftRequest request, StreamDonationContextResponse context) {
        var gift = giftRepository.findById(request.giftId())
                .orElseThrow(() -> new AppException(ErrorCode.VIRTUAL_GIFT_NOT_FOUND));
        if (gift.getStatus() != VirtualGiftEntity.Status.ACTIVE) {
            throw new AppException(ErrorCode.VIRTUAL_GIFT_INACTIVE);
        }
        var now = LocalDateTime.now(paymentClock).truncatedTo(ChronoUnit.MICROS);
        // Reserve the donor key before competing for funds, even when only one gift is affordable.
        var donation = donationRepository.saveAndFlush(DonationEntity.builder()
                .donorUserId(donorUserId)
                .streamerId(context.streamerId())
                .channelId(context.channelId())
                .streamId(request.streamId())
                .giftId(gift.getId())
                .idempotencyKey(request.idempotencyKey())
                .coinPrice(gift.getCoinPrice())
                .status(DonationEntity.Status.COMPLETED)
                .createdAt(now)
                .build());

        if (userWalletRepository.debitBalanceByUserId(donorUserId, gift.getCoinPrice(), now) == 0) {
            throw new AppException(userWalletRepository.existsByUserId(donorUserId)
                    ? ErrorCode.INSUFFICIENT_BALANCE : ErrorCode.WALLET_NOT_FOUND);
        }
        var wallet = userWalletRepository.findByUserId(donorUserId)
                .orElseThrow(() -> new AppException(ErrorCode.WALLET_NOT_FOUND));
        walletTransactionRepository.save(WalletTransactionEntity.builder()
                .walletId(wallet.getId())
                .type(WalletTransactionEntity.Type.GIFT_PURCHASE)
                .amount(gift.getCoinPrice())
                .referenceId(donation.getId())
                .createdAt(now)
                .build());

        streamerWalletRepository.insertIfAbsent(context.streamerId());
        if (streamerWalletRepository.creditAvailableBalanceByStreamerId(context.streamerId(), gift.getCoinPrice(), now) != 1) {
            throw new AppException(ErrorCode.PAYMENT_DATA_UNAVAILABLE);
        }

        return response(donation, gift, wallet.getBalance());
    }

    private DonateGiftResponse replay(DonationEntity donation, DonateGiftRequest request) {
        if (!donation.getStreamId().equals(request.streamId()) || !donation.getGiftId().equals(request.giftId())) {
            throw new AppException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        var wallet = userWalletRepository.findByUserId(donation.getDonorUserId())
                .orElseThrow(() -> new AppException(ErrorCode.WALLET_NOT_FOUND));
        var gift = giftRepository.findById(donation.getGiftId())
                .orElseThrow(() -> new AppException(ErrorCode.VIRTUAL_GIFT_NOT_FOUND));
        return response(donation, gift, wallet.getBalance());
    }

    private DonateGiftResponse response(DonationEntity donation, VirtualGiftEntity gift, long remainingBalance) {
        return new DonateGiftResponse(donation.getId(), donation.getStreamId(), donation.getChannelId(),
                donation.getStreamerId(), donation.getGiftId(), gift.getName(), gift.getImageUrl(),
                donation.getCoinPrice(), remainingBalance, donation.getCreatedAt());
    }
}
