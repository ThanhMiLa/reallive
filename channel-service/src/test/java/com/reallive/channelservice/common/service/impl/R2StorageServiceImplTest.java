package com.reallive.channelservice.common.service.impl;

import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class R2StorageServiceImplTest {

    private S3Client client;
    private R2StorageServiceImpl storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        storage = new R2StorageServiceImpl(client);
        ReflectionTestUtils.setField(storage, "bucketName", "channel-banners");
        ReflectionTestUtils.setField(storage, "publicBaseUrl", "https://images.example.com/");
    }

    @Test
    void uploadsImageBytesAndMetadataToTheConfiguredBucket() throws Exception {
        byte[] content = {1, 2, 3};
        String key = "channels/" + UUID.randomUUID() + "/banners/" + UUID.randomUUID() + ".png";
        storage.upload(key, content, "image/png");

        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertEquals("channel-banners", request.getValue().bucket());
        assertEquals(key, request.getValue().key());
        assertEquals("image/png", request.getValue().contentType());
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            assertArrayEquals(content, stream.readAllBytes());
        }
        assertEquals("https://images.example.com/" + key, storage.getPublicUrl(key));
    }

    @Test
    void mapsProviderFailuresToTheBannerUploadError() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("Provider unavailable").build());
        AppException exception = assertThrows(AppException.class, () -> storage.upload("banner.png", new byte[]{1}, "image/png"));
        assertEquals(ErrorCode.BANNER_UPLOAD_FAILED, exception.getErrorCode());
    }

    @Test
    void deletesOnlyBannersManagedForTheRequestedChannel() {
        UUID channelId = UUID.randomUUID();
        String filename = UUID.randomUUID() + ".png";
        String key = "channels/" + channelId + "/banners/" + filename;
        String ownPrefix = storage.getPublicUrl("channels/" + channelId + "/banners/");
        for (String url : List.of("https://external.example.com/banner.png",
                storage.getPublicUrl("channels/" + UUID.randomUUID() + "/banners/" + filename),
                ownPrefix + "../" + filename, ownPrefix + filename + "?version=1")) {
            storage.deleteManagedBanner(url, channelId);
        }
        storage.deleteManagedBanner(null, channelId);
        verifyNoInteractions(client);

        storage.deleteManagedBanner(storage.getPublicUrl(key), channelId);
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertEquals("channel-banners", request.getValue().bucket());
        assertEquals(key, request.getValue().key());
    }

    @Test
    void cleanupFailureDoesNotThrowAfterDatabaseCommit() {
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build());
        assertDoesNotThrow(() -> storage.deleteQuietly("banner.png"));
    }
}
