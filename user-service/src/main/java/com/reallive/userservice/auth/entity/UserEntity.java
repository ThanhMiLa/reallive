package com.reallive.userservice.auth.entity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
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
@Table(name = "users",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
            @UniqueConstraint(name = "uk_users_email", columnNames = "email")
        },
        indexes = @Index(name = "idx_users_status", columnList = "status"))
public class UserEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @Column(name = "username", nullable = false, length = 255)
    String username;

    @Column(name = "email", nullable = false, length = 255)
    String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    @ToString.Exclude
    String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    UserStatus status = UserStatus.ACTIVE;

    @Column(name = "email_verified", nullable = false)
    boolean emailVerified;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "role_id", nullable = false),
            foreignKey = @ForeignKey(name = "fk_user_roles_user"),
            inverseForeignKey = @ForeignKey(name = "fk_user_roles_role"),
            indexes = @Index(name = "idx_user_roles_role_id", columnList = "role_id"))
    @ToString.Exclude
    @Builder.Default
    Set<RoleEntity> roles = new HashSet<>();

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return UserEntity.class.hashCode();
    }
}
