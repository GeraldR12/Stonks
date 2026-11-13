package me.geraldr12;

import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.v110.MigrationState.MigrationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationStartupSafetyTest {
    @TempDir Path directory;

    @Test
    void eligibleMigrationFailurePropagatesAndLogsSevere() throws Exception {
        Path state = directory.resolve("migration/v110/state.json");
        Files.createDirectories(state.getParent());
        Files.writeString(state, "{not json");
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getString("Stonks.Version")).thenReturn("1.0.0");
        PlayersService players = mock(PlayersService.class);
        doReturn(mock(PlayersRepository.class)).when(players).getRepository();
        Logger logger = mock(Logger.class);
        MigrationHandler handler = new MigrationHandler(config,
                new PluginDescriptionFile("Stonks", "2.0.0", "me.geraldr12.Stonks"), directory.toFile(), logger,
                mock(CompaniesService.class), players, mock(SignsService.class));

        assertThrows(MigrationException.class, handler::checkMigrations);
        verify(logger).severe(startsWith("The plugin was not able to migrate the data:"));
    }

    @Test
    void migrationRunsAfterJournalAndBeforeCommandsEventsAndSchedulers() throws Exception {
        String source = Files.readString(Path.of("src/main/java/me/geraldr12/Stonks.java"));
        String enable = source.substring(source.indexOf("public void onEnable()"), source.indexOf("public void onDisable()"));
        int migration = enable.indexOf("migrationHandler.checkMigrations(freshInstallation);");
        int fresh = enable.indexOf("MigrationHandler.isFreshInstallation(getDataFolder())");
        assertTrue(fresh >= 0 && fresh < enable.indexOf("configureConfig();")
                && fresh < enable.indexOf("initializeCompaniesData();"), "fresh install is decided before any file is written");
        assertTrue(migration > enable.indexOf("if (!initializeTransactionJournal())"));
        for (String registration : new String[]{"new MainCommand", "registerEvents();", "registerSchedulers();", "checkForUpdates();"}) {
            assertTrue(migration < enable.indexOf(registration), registration);
        }
        assertEquals(1, enable.split("migrationHandler.checkMigrations\\(", -1).length - 1);
    }

    private boolean shouldCreateExample() throws Exception {
        var decision = Stonks.class.getDeclaredMethod("shouldCreateDefaultCompany", File.class);
        decision.setAccessible(true);
        return (boolean) decision.invoke(null, directory.toFile());
    }

    @Test
    void freshBootstrapCreatesExampleOnlyWhenAbsent() throws Exception {
        assertTrue(shouldCreateExample());
        Files.createDirectories(directory.resolve("companies"));
        Files.writeString(directory.resolve("companies/0.yml"), "preserve");
        assertFalse(shouldCreateExample());
    }

    @Test
    void migrationStateSuppressesExampleInEveryPhaseIncludingDone() throws Exception {
        Path state = directory.resolve("migration/v110/state.json");
        Files.createDirectories(state.getParent());
        for (String phase : new String[]{"BACKUP", "STAGE", "APPLY", "VERIFY", "ARCHIVE", "DONE"}) {
            Files.writeString(state, "{\"phase\":\"" + phase + "\"}");
            assertFalse(shouldCreateExample(), phase);
        }
        String source = Files.readString(Path.of("src/main/java/me/geraldr12/Stonks.java"));
        String initialize = source.substring(source.indexOf("private void initializeCompaniesData()"), source.indexOf("private boolean initializeTransactionJournal()"));
        assertTrue(initialize.contains("if (shouldCreateDefaultCompany(getDataFolder()))"));
    }
}
