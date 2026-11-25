package com.reallive.paymentservice.donation.entity;

import com.reallive.paymentservice.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "virtual_gifts", uniqueConstraints =
        @UniqueConstraint(name = "uk_virtual_gifts_name", columnNames = "name"))
public class VirtualGiftEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(nullable = false, length = 100)
    String name;

    @Column(nullable = false, length = 500)
    String imageUrl;

    @Column(nullable = false)
    long coinPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    Status status;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VirtualGiftEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return VirtualGiftEntity.class.hashCode();
    }

    public enum Status {
        ACTIVE, INACTIVE
    }
}
