package com.reallive.userservice.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.io.Serializable;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserCategoryPreferenceId implements Serializable {

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(name = "category_id", nullable = false)
    UUID categoryId;
}
