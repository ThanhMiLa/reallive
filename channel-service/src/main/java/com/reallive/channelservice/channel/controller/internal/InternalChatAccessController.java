package com.reallive.channelservice.channel.controller.internal;

import com.reallive.channelservice.channel.dto.request.ChatAccessRequest;
import com.reallive.channelservice.channel.dto.response.ChatAccessResponse;
import com.reallive.channelservice.channel.service.ChatAccessService;
import com.reallive.channelservice.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/channels")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalChatAccessController {
    ChatAccessService service;

    @PostMapping("/{channelId}/chat-access")
    public ApiResponse<ChatAccessResponse> access(@PathVariable UUID channelId,
            @Valid @RequestBody ChatAccessRequest request) {
        return ApiResponse.<ChatAccessResponse>builder().status(200).code(1000).message("Channel access resolved")
                .result(service.resolve(channelId, request.userId())).timestamp(Instant.now().toString()).build();
    }
}
