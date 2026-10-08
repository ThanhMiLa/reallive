package com.reallive.userservice.common.service.impl;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.service.R2StorageService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class R2StorageServiceImpl implements R2StorageService {

    S3Client r2Client;

    @NonFinal @Value("${storage.r2.bucket-name}")
    String bucketName;

    @NonFinal @Value("${storage.r2.public-base-url}")
    String publicBaseUrl;

    @Override
    public void upload(String objectKey, byte[] content, String contentType) {
        try {
            r2Client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException exception) {
            log.error("R2 upload failed for object {}", objectKey);
            throw new AppException(ErrorCode.AVATAR_UPLOAD_FAILED);
        }
    }

    @Override
    public String getPublicUrl(String objectKey) {
        return publicBaseUrl + "/" + objectKey;
    }

    /** Cleanup must not turn a committed profile update into a failed response. */
    @Override
    public void deleteQuietly(String objectKey) {
        try {
            r2Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build());
        } catch (SdkException exception) {
            log.warn("R2 cleanup failed for object {}; retry cleanup is required", objectKey);
        }
    }

    @Override
    public void deleteManagedAvatar(String avatarUrl, UUID userId) {
        String prefix = getPublicUrl("avatars/" + userId + "/");
        if (avatarUrl == null || !avatarUrl.startsWith(prefix)) {
            return;
        }
        String filename = avatarUrl.substring(prefix.length());
        if (filename.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)")) {
            deleteQuietly(avatarUrl.substring(publicBaseUrl.length() + 1));
        }
    }
}
