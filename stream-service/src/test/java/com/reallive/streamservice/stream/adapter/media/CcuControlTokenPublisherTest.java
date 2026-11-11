package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.stream.configuration.CcuProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CcuControlTokenPublisherTest {
    @TempDir Path directory;
    @Test void rotatesCredentialPrivatelyAndReplacesFileWithoutLeavingTemporarySecrets() throws Exception {
        var properties = new CcuProperties(); properties.setControlTokenDirectory(directory);
        var issuer = mock(MediaJwtIssuer.class);
        when(issuer.issueControlApi()).thenReturn(new MediaToken("first", Instant.now().plusSeconds(300)), new MediaToken("second", Instant.now().plusSeconds(300)));
        var publisher = new CcuControlTokenPublisher(properties, issuer);
        publisher.publish();
        assertEquals("first", Files.readString(directory.resolve("control.jwt")));
        publisher.publish();
        assertEquals("second", Files.readString(directory.resolve("control.jwt")));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("control.jwt")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
