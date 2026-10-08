package com.reallive.userservice.user.util;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

public final class AvatarValidator {

    private static final long MAX_PIXELS = 16_000_000;

    private AvatarValidator() {
    }

    public static AvatarImage validate(MultipartFile avatar) {
        if (avatar.isEmpty()) {
            throw new AppException(ErrorCode.AVATAR_INVALID);
        }
        return readImage(avatar);
    }

    private static AvatarImage readImage(MultipartFile avatar) {
        try {
            byte[] content = avatar.getBytes();
            try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw new AppException(ErrorCode.AVATAR_INVALID);
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    String extension = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                        case "jpeg", "jpg" -> "jpg";
                        case "png" -> "png";
                        case "webp" -> "webp";
                        default -> throw new AppException(ErrorCode.AVATAR_INVALID);
                    };
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (pixels <= 0 || pixels > MAX_PIXELS || reader.read(0) == null) {
                        throw new AppException(ErrorCode.AVATAR_INVALID);
                    }
                    String contentType = extension.equals("jpg") ? "image/jpeg" : "image/" + extension;
                    return new AvatarImage(content, contentType, extension);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new AppException(ErrorCode.AVATAR_INVALID);
        }
    }

    public record AvatarImage(
            byte[] content,
            String contentType,
            String extension
    ) {}
}
