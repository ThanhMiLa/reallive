package com.reallive.paymentservice.deposit.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.deposit.adapter.vnpay.VnpayPayAdapter;
import com.reallive.paymentservice.deposit.adapter.vnpay.VnpayRequestSigner;
import com.reallive.paymentservice.deposit.configuration.VnpayProperties;
import com.reallive.paymentservice.deposit.dto.response.CoinPackageResponse;
import com.reallive.paymentservice.deposit.dto.response.CreateVnpayDepositResponse;
import com.reallive.paymentservice.deposit.dto.response.DepositStatusResponse;
import com.reallive.paymentservice.deposit.entity.CoinDepositPackage;
import com.reallive.paymentservice.deposit.entity.PaymentTransactionEntity;
import com.reallive.paymentservice.deposit.entity.TransactionStatus;
import com.reallive.paymentservice.deposit.repository.PaymentTransactionRepository;
import com.reallive.paymentservice.deposit.service.DepositService;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DepositServiceImpl implements DepositService {

    private static final SecureRandom ORDER_CODES = new SecureRandom();
    // Keep numeric order references exactly representable in browser clients.
    private static final long MAX_ORDER_CODE = (1L << 53) - 1;

    private static final List<CoinPackageResponse> PACKAGES = Arrays.stream(CoinDepositPackage.values())
            .map(pkg -> new CoinPackageResponse(pkg.name(), pkg.getDisplayName(), pkg.getAmountVnd(), pkg.getCoinAmount()))
            .toList();

    PaymentTransactionRepository paymentRepository;
    UserWalletRepository walletRepository;
    VnpayPayAdapter checkoutAdapter;
    VnpayProperties properties;
    Clock paymentClock;

    @Override
    public List<CoinPackageResponse> getPackages() {
        return PACKAGES;
    }

    @Override
    @Transactional
    public CreateVnpayDepositResponse createVnpayDeposit(UUID userId, String packageCode, String ipAddress) {
        CoinDepositPackage coinPackage = CoinDepositPackage.fromCode(packageCode);
        UserWalletEntity wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.WALLET_NOT_FOUND));
        Instant now = paymentClock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(properties.expireMinutes(), ChronoUnit.MINUTES);
        PaymentTransactionEntity payment = PaymentTransactionEntity.builder()
                .wallet(wallet)
                .orderCode(ORDER_CODES.nextLong(1, MAX_ORDER_CODE + 1))
                .provider(PaymentTransactionEntity.Provider.VNPAY)
                .packageCode(coinPackage)
                .amount(coinPackage.getAmountVnd())
                .coinAmount(coinPackage.getCoinAmount())
                .status(TransactionStatus.PENDING)
                .providerCreateDate(VnpayRequestSigner.formatDate(now))
                .expiresAt(LocalDateTime.ofInstant(expiresAt, paymentClock.getZone()))
                .build();

        String paymentUrl = checkoutAdapter.paymentUrl(payment, expiresAt, ipAddress);
        paymentRepository.save(payment);
        return new CreateVnpayDepositResponse(payment.getId(), payment.getOrderCode(), coinPackage.name(),
                payment.getAmount(), payment.getCoinAmount(), payment.getStatus(), expiresAt, paymentUrl);
    }

    @Override
    @Transactional(readOnly = true)
    public DepositStatusResponse getStatus(UUID userId, long orderCode) {
        PaymentTransactionEntity payment = paymentRepository.findOwnedOrder(orderCode, userId)
                .orElseThrow(() -> new AppException(ErrorCode.PAYMENT_TRANSACTION_NOT_FOUND));
        return new DepositStatusResponse(payment.getId(), payment.getOrderCode(), payment.getPackageCode().name(),
                payment.getAmount(), payment.getCoinAmount(), payment.getStatus(), payment.getProviderTransactionNo(),
                payment.getProviderResponseCode(), payment.getWallet().getBalance(),
                payment.getPaidAt() == null ? null : toInstant(payment.getPaidAt()),
                toInstant(payment.getCreatedAt()), toInstant(payment.getExpiresAt()));
    }

    private Instant toInstant(LocalDateTime timestamp) {
        return timestamp.atZone(paymentClock.getZone()).toInstant();
    }
}
