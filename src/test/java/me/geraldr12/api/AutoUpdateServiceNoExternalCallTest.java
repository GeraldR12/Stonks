package me.geraldr12.api;

import me.geraldr12.Stonks;
import me.geraldr12.api.entities.PluginReleaseEntity;
import me.geraldr12.api.services.AutoUpdateService;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AutoUpdateServiceNoExternalCallTest {

    private static final Path SOURCES = Path.of("src/main/java/me/geraldr12/api");

    private AutoUpdateService service() throws ReflectiveOperationException {
        Stonks plugin = mock(Stonks.class);
        Field description = JavaPlugin.class.getDeclaredField("description");
        description.setAccessible(true);
        description.set(plugin, new PluginDescriptionFile("Stonks", "2.9.1", "me.geraldr12.Stonks"));
        return new AutoUpdateService(plugin);
    }

    @Test
    void latestReleaseCompletesEmptyWithoutWaiting() throws Exception {
        CompletableFuture<PluginReleaseEntity> release = service().getLatestRelease();

        assertTrue(release.isDone());
        assertNull(release.get());
    }

    @Test
    void emptyReleaseMeansNoUpdate() throws Exception {
        CompletableFuture<Boolean> available = service().isUpdateAvailable();

        assertTrue(available.isDone());
        assertFalse(available.get());
    }

    @Test
    void updaterDoesNotOpenConnections() throws IOException {
        String updater = Files.readString(SOURCES.resolve("services/AutoUpdateService.java"));

        assertFalse(updater.contains("executeRequest"), "the updater must not start a request");
        assertFalse(updater.contains("PluginReleaseAPIEndpoint"), "the updater must not build the endpoint");
    }

    @Test
    void foreignReleaseIdentityIsGone() throws IOException {
        String endpoint = Files.readString(SOURCES.resolve("endpoints/implementation/PluginReleaseAPIEndpoint.java"));

        assertFalse(endpoint.contains("Hugo1307"), "must not reference another plugin repository");
        assertFalse(endpoint.contains("http"), "must not declare an external address");
    }
}
