package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.impl.AuthStateSyncProcessor;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "auth.state.backfill-enabled", havingValue = "true")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthStateBackfill implements ApplicationRunner {

    UserRepository userRepository;
    AuthStateSyncProcessor processor;

    @Override
    public void run(ApplicationArguments arguments) {
        int page = 0;
        Page<UUID> users;
        do {
            users = userRepository.findAllIds(PageRequest.of(page++, 100, Sort.by("id")));
            users.forEach(processor::backfill);
        } while (users.hasNext());
    }
}
