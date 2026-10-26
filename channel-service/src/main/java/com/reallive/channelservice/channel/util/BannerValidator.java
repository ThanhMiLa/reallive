package com.reallive.channelservice.channel.util;

import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

public final class BannerValidator {

    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;

    private BannerValidator() {
    }

    public static BannerImage validate(MultipartFile banner) {
        if (banner.getSize() > MAX_BYTES) {
            throw new AppException(ErrorCode.BANNER_TOO_LARGE);
        }
        try {
            byte[] content = banner.getBytes();
            try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw new AppException(ErrorCode.BANNER_INVALID);
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    String extension = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                        case "jpeg", "jpg" -> "jpg";
                        case "png" -> "png";
                        case "webp" -> "webp";
                        default -> throw new AppException(ErrorCode.BANNER_INVALID);
                    };
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (pixels <= 0 || pixels > MAX_PIXELS || reader.read(0) == null) {
                        throw new AppException(ErrorCode.BANNER_INVALID);
                    }
                    String contentType = extension.equals("jpg") ? "image/jpeg" : "image/" + extension;
                    return new BannerImage(content, contentType, extension);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new AppException(ErrorCode.BANNER_INVALID);
        }
    }

    public record BannerImage(
            byte[] content,
            String contentType,
            String extension
    ) {}
}
