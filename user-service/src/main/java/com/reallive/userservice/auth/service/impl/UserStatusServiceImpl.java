package com.reallive.userservice.auth.service.impl;

import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.UserStatusService;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserStatusServiceImpl implements UserStatusService {

    UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public UserStatus getStatus(UUID userId) {
        return users.findStatusById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }
}
