package com.reallive.streamservice.stream.controller.internal;

import com.reallive.streamservice.stream.dto.response.ChatContextResponse;
import com.reallive.streamservice.stream.service.ChatContextService;
import com.reallive.streamservice.common.dto.ApiResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/streams")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatContextController {
    ChatContextService service;

    @GetMapping("/{sessionId}/chat-context")
    public ApiResponse<ChatContextResponse> context(@PathVariable UUID sessionId) {
        return ApiResponse.<ChatContextResponse>builder().status(200).code(1000).message("Chat context resolved")
                .result(service.context(sessionId)).timestamp(Instant.now().toString()).build();
    }
}
