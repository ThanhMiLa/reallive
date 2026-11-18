package com.reallive.userservice.auth.service.impl;

import com.reallive.userservice.auth.dto.response.UserIdentityResponse;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.UserStatusService;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.PageRequest;
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
    public UserIdentityResponse.Page searchIdentities(String displayName, int page, int size) {
        String fragment = displayName.strip();
        if (fragment.isEmpty() || fragment.length() > 100) {
            throw new AppException(ErrorCode.DISPLAY_NAME_INVALID);
        }
        if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE) {
            throw new AppException(ErrorCode.USER_SEARCH_PAGINATION_INVALID);
        }

        String pattern = "%" + fragment.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var identities = users.searchIdentitiesByDisplayName(pattern, PageRequest.of(page, size));
        return new UserIdentityResponse.Page(identities.getContent(), page, size,
                identities.getTotalElements(), identities.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public UserStatus getStatus(UUID userId) {
        return users.findStatusById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }
}
