package com.reallive.channelservice.common.service.impl;

import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import com.reallive.channelservice.common.service.R2StorageService;
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

    @NonFinal
    @Value("${storage.r2.bucket-name}")
    String bucketName;

    @NonFinal
    @Value("${storage.r2.public-base-url}")
    String publicBaseUrl;

    @Override
    public String getPublicUrl(String objectKey) {
        return publicBaseUrl.replaceAll("/+$", "") + "/" + objectKey;
    }

    @Override
    public void upload(String objectKey, byte[] content, String contentType) {
        try {
            r2Client.putObject(PutObjectRequest.builder().bucket(bucketName).key(objectKey).contentType(contentType).build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException exception) {
            log.error("R2 upload failed for object {}", objectKey);
            throw new AppException(ErrorCode.BANNER_UPLOAD_FAILED);
        }
    }

    @Override
    public void deleteQuietly(String objectKey) {
        try {
            r2Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build());
        } catch (SdkException exception) {
            log.warn("R2 cleanup failed for object {}; retry cleanup is required", objectKey);
        }
    }

    @Override
    public void deleteManagedBanner(String bannerUrl, UUID channelId) {
        String objectPrefix = "channels/" + channelId + "/banners/";
        String urlPrefix = getPublicUrl(objectPrefix);
        if (bannerUrl == null || !bannerUrl.startsWith(urlPrefix)) {
            return;
        }
        String filename = bannerUrl.substring(urlPrefix.length());
        if (filename.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)")) {
            deleteQuietly(objectPrefix + filename);
        }
    }
}
