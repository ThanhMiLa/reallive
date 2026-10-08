package com.reallive.userservice.common.service;

import java.util.UUID;

public interface R2StorageService {
    void upload(String objectKey, byte[] content, String contentType);

    String getPublicUrl(String objectKey);

    void deleteQuietly(String objectKey);

    void deleteManagedAvatar(String avatarUrl, UUID userId);
}
