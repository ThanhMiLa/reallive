package com.reallive.userservice.auth.repository;

import com.reallive.userservice.auth.entity.UserGoogleAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserGoogleAccountRepository extends JpaRepository<UserGoogleAccountEntity, UUID> {
}
