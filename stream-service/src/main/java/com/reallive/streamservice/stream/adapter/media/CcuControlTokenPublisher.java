package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.stream.configuration.CcuProperties;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "stream.ccu", name = "enabled", havingValue = "true")
public class CcuControlTokenPublisher {
    CcuProperties properties;
    MediaJwtIssuer issuer;

    @PostConstruct
    void initialize() {
        try {
            writeCredential();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot initialize CCU control credential directory");
        }
    }

    @Scheduled(fixedDelayString = "${stream.ccu.token-publish-delay:60000}", scheduler = "ccuTaskScheduler")
    public void publish() {
        try {
            writeCredential();
        } catch (IOException exception) {
            Metrics.counter("ccu.control.credential.failures").increment();
            log.error("Cannot publish CCU control credential; hook lookups may fail");
        }
    }

    private void writeCredential() throws IOException {
        var directory = properties.getControlTokenDirectory();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            temporary = Files.createTempFile(directory, ".control-", ".jwt",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(temporary, issuer.issueControlApi().value());
            Files.move(temporary, directory.resolve("control.jwt"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    log.warn("CCU credential temporary file cleanup failed");
                }
            }
        }
    }
}
