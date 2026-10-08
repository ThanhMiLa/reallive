package com.reallive.userservice.common.service.impl;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.service.R2StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class R2StorageServiceImplTest {

    private S3Client client;
    private R2StorageServiceImpl storage;
    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String filename = "22222222-2222-2222-2222-222222222222.png";

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        storage = new R2StorageServiceImpl(client);
        ReflectionTestUtils.setField(storage, "bucketName", "test-avatars");
        ReflectionTestUtils.setField(storage, "publicBaseUrl", "https://images.example.com");
    }

    @Test
    void uploadsContentToConfiguredBucketWithDetectedContentType() throws Exception {
        byte[] content = {1, 2, 3};
        storage.upload("avatars/test.png", content, "image/png");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertEquals("test-avatars", request.getValue().bucket());
        assertEquals("avatars/test.png", request.getValue().key());
        assertEquals("image/png", request.getValue().contentType());
        try (var input = body.getValue().contentStreamProvider().newStream()) {
            assertArrayEquals(content, input.readAllBytes());
        }
        assertEquals("https://images.example.com/avatars/test.png", storage.getPublicUrl("avatars/test.png"));
    }

    @Test
    void translatesUploadFailuresWithoutExposingProviderDetails() {
        doThrow(SdkClientException.create("Provider error")).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        AppException exception = assertThrows(AppException.class, () -> storage.upload("avatar.png", new byte[]{1}, "image/png"));
        assertEquals(ErrorCode.AVATAR_UPLOAD_FAILED, exception.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "webp"})
    void deletesOnlyManagedAvatarOfTheCurrentUser(String extension) {
        String key = "avatars/" + userId + "/" + filename.replace(".png", "." + extension);
        storage.deleteManagedAvatar("https://images.example.com/" + key, userId);
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("test-avatars").key(key).build());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "https://other.example.com/avatars/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.png",
            "https://images.example.com/avatars/33333333-3333-3333-3333-333333333333/22222222-2222-2222-2222-222222222222.png",
            "https://images.example.com/avatars/11111111-1111-1111-1111-111111111111/../private.png",
            "https://images.example.com/avatars/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.png?other=true",
            "https://images.example.com/old-avatar.png",
            "https://images.example.com.evil.test/avatars/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.png",
            "https://images.example.com/avatars/11111111-1111-1111-1111-111111111111/",
            "https://images.example.com/avatars/11111111-1111-1111-1111-111111111111/subfolder/22222222-2222-2222-2222-222222222222.png",
            "https://images.example.com/avatars/11111111-1111-1111-1111-111111111111/%2e%2e/private.png",
            "https://images.example.com/avatars/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.svg",
            "https://images.example.com/avatars/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.png#fragment"
    })
    void skipsExternalOrUnmanagedUrls(String url) {
        storage.deleteManagedAvatar(url, userId);
        verifyNoInteractions(client);
    }

    @Test
    void cleanupFailuresDoNotEscape() {
        doThrow(SdkClientException.create("Provider error")).when(client).deleteObject(any(DeleteObjectRequest.class));
        assertDoesNotThrow(() -> storage.deleteQuietly("avatar.png"));
    }

    @Test
    void translatesStorageServiceErrorsToUploadFailed() {
        doThrow(S3Exception.builder().statusCode(503).message("Storage unavailable").build())
                .when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        AppException exception = assertThrows(AppException.class,
                () -> storage.upload("avatar.png", new byte[] {1}, "image/png"));

        assertEquals(ErrorCode.AVATAR_UPLOAD_FAILED, exception.getErrorCode());
    }

    @Test
    void managedAvatarCleanupDoesNotPropagateStorageServiceErrors() {
        String key = "avatars/" + userId + "/" + filename;
        doThrow(S3Exception.builder().statusCode(503).message("Storage unavailable").build())
                .when(client).deleteObject(any(DeleteObjectRequest.class));

        assertDoesNotThrow(() -> storage.deleteManagedAvatar("https://images.example.com/" + key, userId));

        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("test-avatars").key(key).build());
    }

    @Test
    void springInjectsLombokConstructorAndValueConfigurationThroughServiceInterface() {
        new ApplicationContextRunner()
                .withUserConfiguration(R2StorageServiceImpl.class)
                .withBean(S3Client.class, () -> client)
                .withPropertyValues("storage.r2.bucket-name=configured-avatars",
                        "storage.r2.public-base-url=https://configured.example.com")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    R2StorageService service = context.getBean(R2StorageService.class);
                    assertSame(context.getBean(R2StorageServiceImpl.class), service);
                    assertEquals("https://configured.example.com/avatars/test.png", service.getPublicUrl("avatars/test.png"));
                    service.upload("avatars/test.png", new byte[] {1}, "image/png");
                    ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
                    verify(client).putObject(request.capture(), any(RequestBody.class));
                    assertEquals("configured-avatars", request.getValue().bucket());
                });
    }
}
