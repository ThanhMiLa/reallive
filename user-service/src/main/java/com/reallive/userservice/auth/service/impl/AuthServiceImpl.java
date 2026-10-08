package com.reallive.userservice.auth.service.impl;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.service.AuthService;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.util.UserValidator;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
@FieldDefaults(level = lombok.AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class AuthServiceImpl implements AuthService {

    UserRepository userRepository;
    UserMapper userMapper;
    PasswordEncoder passwordEncoder;
    RoleRepository roleRepository;
    StringRedisTemplate redisTemplate;
    ProfileRepository profileRepository;


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

        return generateAuthResponse(userEntity);
    }

    @Override
    public void logout(String accessToken, String refreshToken) {

        if(accessToken == null || accessToken.isBlank() || refreshToken == null || refreshToken.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        processTokenInvalidation(accessToken, "ACCESS");
        processTokenInvalidation(refreshToken, "REFRESH");
    }

    @Override
    @Transactional
    public AuthResponse refresh(String token) {
        if (token == null || token.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        processTokenInvalidation(token, "REFRESH");

        // Cấp lại token mới
        try{
            JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
            String userId = claims.getSubject();
            UserEntity userEntity = userRepository.findById(UUID.fromString(userId))
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

            UserValidator.validateActive(userEntity);

            return generateAuthResponse(userEntity);

        }catch (ParseException parseException){
            throw new AppException(ErrorCode.UNAUTHENTICATED);
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
        userEntity.setRoles(new HashSet<>(Set.of(roleRepository.findByName("USER")
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND)))));
        userEntity = userRepository.save(userEntity);

        ProfileEntity profile = buildProfileEntity(userEntity);
        profileRepository.save(profile);

        AuthRequest authenticationRequest = AuthRequest.builder()
                .username(registerRequest.getUsername())
                .password(registerRequest.getPassword())
                .build();

        return login(authenticationRequest);

    }

    private ProfileEntity buildProfileEntity(UserEntity userEntity) {
        return ProfileEntity.builder()
                .user(userEntity)
                .displayName(userEntity.getUsername())
                .build();
    }

    private void processTokenInvalidation(String token, String type) {
        try {
            JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
            Duration remainingLifetime = Duration.between(Instant.now(), claims.getExpirationTime().toInstant());
            if (remainingLifetime.toMillis() <= 0) {
                if ("REFRESH".equals(type)) {
                    throw new AppException(ErrorCode.UNAUTHENTICATED);
                }
                return;
            }

            String blacklistKey = "auth:revoked:" + type.toLowerCase(Locale.ROOT) + ":" + claims.getJWTID();
            Boolean consumed = redisTemplate.opsForValue()
                    .setIfAbsent(blacklistKey, "1", remainingLifetime);
            if (!Boolean.TRUE.equals(consumed)) {
                throw new AppException(ErrorCode.UNAUTHENTICATED);
            }

            String storedValue = redisTemplate.opsForValue().get(blacklistKey);
            Long ttlSeconds = redisTemplate.getExpire(blacklistKey, TimeUnit.SECONDS);
            log.info("Redis blacklist readback: key={}, value={}, ttlSeconds={}",
                    blacklistKey, storedValue, ttlSeconds);
        } catch (ParseException exception) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
    }

    private AuthResponse generateAuthResponse(UserEntity userEntity) {
        String accessToken = generateToken(userEntity, false);
        String refreshToken = generateToken(userEntity, true);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private String generateToken(UserEntity userEntity, boolean isRefresh) {
        JWSHeader header = new JWSHeader(JWSAlgorithm.HS512);

        JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                .subject(userEntity.getId().toString())
                .issuer("reallive.com")
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
            jwsObject.sign(new MACSigner(SIGNER_KEY.getBytes(StandardCharsets.UTF_8)));
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
