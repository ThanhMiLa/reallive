package com.reallive.userservice.auth.repository;

import com.reallive.userservice.auth.entity.InvalidatedTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface InvalidatedTokenRepository extends JpaRepository<InvalidatedTokenEntity, UUID> {
}
