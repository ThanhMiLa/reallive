package com.reallive.paymentservice.donation.repository;

import com.reallive.paymentservice.donation.entity.VirtualGiftEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VirtualGiftRepository extends JpaRepository<VirtualGiftEntity, UUID> {

    List<VirtualGiftEntity> findAllByStatusOrderByCoinPriceAscNameAsc(VirtualGiftEntity.Status status);
}
