package com.reallive.paymentservice.donation.repository;

import com.reallive.paymentservice.donation.entity.DonationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DonationRepository extends JpaRepository<DonationEntity, UUID> {

    Optional<DonationEntity> findByDonorUserIdAndIdempotencyKey(UUID donorUserId, UUID idempotencyKey);
}
