package com.reallive.userservice.auth.service.impl;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.entity.InvalidatedTokenEntity;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.InvalidatedTokenRepository;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.service.AuthService;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;


@Service
@RequiredArgsConstructor
@FieldDefaults(level = lombok.AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class AuthServiceImpl implements AuthService {

    UserRepository userRepository;
    UserMapper userMapper;
    PasswordEncoder passwordEncoder;
    RoleRepository roleRepository;
    InvalidatedTokenRepository invalidatedTokenRepository;

    @NonFinal
    @Value("${jwt.signer-key}")
    String SIGNER_KEY;

    @NonFinal
    @Value("${jwt.ttl_accessToken}")
    long TTL_ACCESS_TOKEN;

    @NonFinal
    @Value("${jwt.ttl_refreshToken}")
    long TTL_REFRESH_TOKEN;


    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(AuthRequest authRequest) {
        UserEntity userEntity = userRepository.findByUsername(authRequest.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        boolean isCorrectPassword = passwordEncoder.matches(authRequest.getPassword(), userEntity.getPasswordHash());

        if(!isCorrectPassword || !userEntity.isActive()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        String accessToken = generateToken(userEntity, false);
        String refreshToken = generateToken(userEntity, true);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    @Override
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            processTokenInvalidation(accessToken, "ACCESS");
        }

        if (refreshToken != null && !refreshToken.isBlank()) {
            processTokenInvalidation(refreshToken, "REFRESH");
        }
    }

    private void processTokenInvalidation(String token, String type) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);

            String jti = signedJWT.getJWTClaimsSet().getJWTID();
            Date expireDate = signedJWT.getJWTClaimsSet().getExpirationTime();

            OffsetDateTime expireOSDT = expireDate.toInstant()
                    .atOffset(ZoneOffset.UTC);

            InvalidatedTokenEntity invalidatedTokenEntity = InvalidatedTokenEntity
                    .builder()
                    .jti(UUID.fromString(jti))
                    .expiryTime(expireOSDT.toLocalDateTime())
                    .build();

            invalidatedTokenRepository.save(invalidatedTokenEntity);

        } catch (Exception e) {
            log.warn("Invalid {} format, skipping blacklist: {}", type, e.getMessage());
        }
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest registerRequest) {
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        if (!Objects.equals(registerRequest.getPassword(), registerRequest.getConfirmPassword())) {
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH);
        }


        UserEntity userEntity = userMapper.toUserEntity(registerRequest);
        userEntity.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword()));
        userEntity.setRoles(Collections.singleton(roleRepository.findByName("USER")
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND)))
        );
        userEntity = userRepository.save(userEntity);


        AuthRequest authenticationRequest = AuthRequest.builder()
                .username(registerRequest.getUsername())
                .password(registerRequest.getPassword())
                .build();

        return login(authenticationRequest);

    }
    private String generateToken(UserEntity userEntity, boolean isRefresh) {
        JWSHeader header = new JWSHeader(JWSAlgorithm.HS512);

        JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                .subject(userEntity.getId().toString())
                .issuer("codelearning.thanhmila.com")
                .issueTime(new Date())
                .expirationTime(new Date(
                        Instant.now().plus(isRefresh ? TTL_REFRESH_TOKEN : TTL_ACCESS_TOKEN, ChronoUnit.SECONDS)
                                .toEpochMilli()))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", buildScope(userEntity))
                .claim("type", isRefresh ? "REFRESH" : "ACCESS")
                .build();

        Payload payload = new Payload(jwtClaimsSet.toJSONObject());

        JWSObject jwsObject = new JWSObject(header, payload);

        try {
            jwsObject.sign(new MACSigner(SIGNER_KEY.getBytes()));
            return jwsObject.serialize();
        } catch (JOSEException e) {
            log.error("Cannot create token!");
            throw new RuntimeException(e);
        }
    }

    private String buildScope(UserEntity userEntity) {
        StringJoiner stringJoiner = new StringJoiner(" ");
        if (!CollectionUtils.isEmpty(userEntity.getRoles())) {
            userEntity.getRoles().forEach(role -> {
                stringJoiner.add("ROLE_" + role.getName());
                if (!role.getPermissions().isEmpty()) {
                    role.getPermissions().forEach(permission -> stringJoiner.add(permission.getName()));
                }
            });
        }
        return stringJoiner.toString();
    }
}
