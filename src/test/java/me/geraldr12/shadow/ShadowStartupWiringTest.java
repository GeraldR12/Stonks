package me.geraldr12.shadow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowStartupWiringTest {

    private String method(String signature, String next) throws IOException {
        String source = Files.readString(Path.of("src/main/java/me/geraldr12/Stonks.java"));
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int end = source.indexOf(next, start + signature.length());
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    @Test
    void registrationReadsTheCurrentConfiguration() throws IOException {
        String body = method("public void registerSchedulers()", "public void stopSchedulers()");

        assertTrue(body.contains("stopSchedulers();"), "reload must close the running observation first");
        assertTrue(body.contains("shadowObservation.register(getConfig());"), "reload must apply the new configuration");
    }

    @Test
    void stoppingClosesTheObservation() throws IOException {
        String body = method("public void stopSchedulers()", "\n    }\n");

        assertTrue(body.contains("shadowObservation.close();"));
    }
}
