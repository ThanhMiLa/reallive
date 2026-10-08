package com.reallive.userservice.auth.entity;

import com.reallive.userservice.common.entity.BaseEntity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "user_google_accounts",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_user_google_accounts_user_id", columnNames = "user_id"),
            @UniqueConstraint(name = "uk_user_google_accounts_google_account_id", columnNames = "google_account_id")
        })
public class UserGoogleAccountEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_google_accounts_user"))
    @ToString.Exclude
    UserEntity user;

    @Column(name = "google_account_id", nullable = false, length = 255)
    String googleAccountId;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserGoogleAccountEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return UserGoogleAccountEntity.class.hashCode();
    }
}
