package com.reallive.streamservice.stream.configuration;

import com.reallive.streamservice.common.configuration.MediaProperties;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CcuConfigurationTest {
    @Test void validatesCredentialAndRenewalWindowBeforeEnabling() {
        var ccu = new CcuProperties();
        var media = new MediaProperties();
        var config = new CcuConfiguration(ccu, media);
        assertDoesNotThrow(config::validate);
        ccu.setEnabled(true);
        ccu.setControlTokenDirectory(Path.of("/tmp/ccu"));
        ccu.setCallbackToken(" ".repeat(32));
        assertThrows(IllegalStateException.class, config::validate);
        ccu.setCallbackToken("valid-ccu-callback-secret-0123456789012345");
        media.setControlTokenTtl(Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, config::validate);
        media.setControlTokenTtl(Duration.ofMinutes(5));
        assertDoesNotThrow(config::validate);
        ccu.setControlTokenDirectory(Path.of("relative"));
        assertThrows(IllegalStateException.class, config::validate);
    }
}
