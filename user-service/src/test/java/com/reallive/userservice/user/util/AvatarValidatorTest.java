package com.reallive.userservice.user.util;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvatarValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg"})
    void detectsImageFormatFromContentRatherThanFilenameOrMimeType(String format) throws Exception {
        byte[] content = image(format);
        var result = AvatarValidator.validate(new MockMultipartFile("avatar", "fake.txt", "text/plain", content));
        assertEquals(format, result.extension());
        assertEquals(format.equals("jpg") ? "image/jpeg" : "image/png", result.contentType());
        assertArrayEquals(content, result.content());
    }

    @Test
    void acceptsWebpContent() {
        byte[] content = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=");
        var result = AvatarValidator.validate(new MockMultipartFile("avatar", "avatar.webp", "image/webp", content));
        assertEquals("webp", result.extension());
        assertEquals("image/webp", result.contentType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not an image", "<svg xmlns='http://www.w3.org/2000/svg'/>", "GIF89a"})
    void rejectsEmptyUnsupportedOrFakeImages(String content) {
        AppException exception = assertThrows(AppException.class, () -> AvatarValidator.validate(
                new MockMultipartFile("avatar", "avatar.png", "image/png", content.getBytes())));
        assertEquals(ErrorCode.AVATAR_INVALID, exception.getErrorCode());
    }

    @Test
    void rejectsTruncatedImages() throws Exception {
        byte[] content = Arrays.copyOf(image("png"), 35);
        AppException exception = assertThrows(AppException.class, () -> AvatarValidator.validate(
                new MockMultipartFile("avatar", "broken.png", "image/png", content)));
        assertEquals(ErrorCode.AVATAR_INVALID, exception.getErrorCode());
    }

    @Test
    void rejectsExcessiveDimensionsBeforeDecodingPixels() throws Exception {
        byte[] content = image("png");
        ByteBuffer.wrap(content).putInt(16, 100_000).putInt(20, 100_000);
        AppException exception = assertThrows(AppException.class, () -> AvatarValidator.validate(
                new MockMultipartFile("avatar", "huge.png", "image/png", content)));
        assertEquals(ErrorCode.AVATAR_INVALID, exception.getErrorCode());
    }

    @Test
    void rejectsAValidImageWithAnUnsupportedFormat() throws Exception {
        AppException exception = assertThrows(AppException.class, () -> AvatarValidator.validate(
                new MockMultipartFile("avatar", "fake.png", "image/png", image("gif"))));

        assertEquals(ErrorCode.AVATAR_INVALID, exception.getErrorCode());
    }

    @Test
    void translatesFileReadErrorsIntoAvatarInvalid() throws Exception {
        MultipartFile avatar = mock(MultipartFile.class);
        when(avatar.getBytes()).thenThrow(new IOException("Unable to read temporary upload"));

        AppException exception = assertThrows(AppException.class, () -> AvatarValidator.validate(avatar));

        assertEquals(ErrorCode.AVATAR_INVALID, exception.getErrorCode());
    }

    private byte[] image(String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
}
