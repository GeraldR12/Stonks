package me.geraldr12;

import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationHandlerFreshInstallTest {
    @TempDir Path directory;

    @Test
    void missingVersionAndEmptyFolderSkipsMigrationWithoutWarning() {
        Fixture fixture = new Fixture(null, "2.9.1");

        fixture.handler.checkMigrations();

        verify(fixture.logger).info("Fresh installation; no data migration needed.");
        fixture.verifyNoMigrationOrWarning();
    }

    @Test
    void emptyVersionAndFoldersWithoutYamlAreFreshInstallations() throws Exception {
        Files.createDirectories(directory.resolve("companies"));
        Files.createDirectories(directory.resolve("players"));
        Files.writeString(directory.resolve("companies/README.txt"), "not company data");
        Files.createDirectory(directory.resolve("players/not-a-file.yml"));
        Fixture fixture = new Fixture("", "2.0.0");

        fixture.handler.checkMigrations();

        verify(fixture.logger).info("Fresh installation; no data migration needed.");
        fixture.verifyNoMigrationOrWarning();
        assertFalse(Files.exists(directory.resolve("migration")));
    }

    @Test
    void versionTwoNeedsNoMigrationOrWarningEvenWithData() throws Exception {
        Files.createDirectories(directory.resolve("companies"));
        Files.writeString(directory.resolve("companies/0.yml"), "existing data");
        Fixture fixture = new Fixture("2.0.0", "2.9.1");

        fixture.handler.checkMigrations();

        verify(fixture.logger).info("No data migration needed from 2.0.0 to 2.9.1.");
        fixture.verifyNoMigrationOrWarning();
        assertEquals("existing data", Files.readString(directory.resolve("companies/0.yml")));
    }

    @Test
    void laterVersionTwoAlsoNeedsNoMigration() {
        Fixture fixture = new Fixture("2.8.0", "2.9.1");

        fixture.handler.checkMigrations();

        verify(fixture.logger).info("No data migration needed from 2.8.0 to 2.9.1.");
        fixture.verifyNoMigrationOrWarning();
    }

    @Test
    void missingVersionWithLegacyCompanyDataKeepsLegacyBehavior() throws Exception {
        assertLegacyDataIsNotFresh("companies");
    }

    @Test
    void missingVersionWithLegacyPlayerDataKeepsLegacyBehavior() throws Exception {
        assertLegacyDataIsNotFresh("players");
    }

    @Test
    void firstStartWithDefaultCompanyAlreadyWrittenIsFresh() throws Exception {
        // Production 13/11: config not saved yet, companies/0.yml just created by onEnable.
        Files.createDirectories(directory.resolve("companies"));
        Files.writeString(directory.resolve("companies/0.yml"), "default company");
        Fixture fixture = new Fixture(null, "2.10.0");

        fixture.handler.checkMigrations(true);

        verify(fixture.logger).info("Fresh installation; no data migration needed.");
        fixture.verifyNoMigrationOrWarning();
        assertEquals("default company", Files.readString(directory.resolve("companies/0.yml")));
    }

    @Test
    void freshInstallationIsDetectedFromTheFolderBeforeStonksWritesAnything() throws Exception {
        assertTrue(MigrationHandler.isFreshInstallation(directory.toFile()));
        assertTrue(MigrationHandler.isFreshInstallation(directory.resolve("missing").toFile()));

        Files.createDirectories(directory.resolve("companies"));
        Files.writeString(directory.resolve("companies/README.txt"), "not company data");
        assertTrue(MigrationHandler.isFreshInstallation(directory.toFile()));

        Files.writeString(directory.resolve("config.yml"), "Stonks: {}");
        assertFalse(MigrationHandler.isFreshInstallation(directory.toFile()));
    }

    @Test
    void legacyDataWithoutConfigIsNotFresh() throws Exception {
        Files.createDirectories(directory.resolve("players"));
        Files.writeString(directory.resolve("players/abc.yml"), "legacy data");
        assertFalse(MigrationHandler.isFreshInstallation(directory.toFile()));
    }

    private void assertLegacyDataIsNotFresh(String folder) throws Exception {
        Files.createDirectories(directory.resolve(folder));
        Files.writeString(directory.resolve(folder).resolve("0.yml"), "legacy data");
        Fixture fixture = new Fixture(null, "2.9.1");

        fixture.handler.checkMigrations();

        verify(fixture.logger).warning("The plugin was not able to migrate the data from version 1.0.0 to 2.9.1.");
        verify(fixture.logger, never()).info("Fresh installation; no data migration needed.");
        assertEquals("legacy data", Files.readString(directory.resolve(folder).resolve("0.yml")));
    }

    private final class Fixture {
        final FileConfiguration config = mock(FileConfiguration.class);
        final Logger logger = mock(Logger.class);
        final CompaniesService companies = mock(CompaniesService.class);
        final PlayersService players = mock(PlayersService.class);
        final SignsService signs = mock(SignsService.class);
        final MigrationHandler handler;

        Fixture(String oldVersion, String currentVersion) {
            when(config.getString("Stonks.Version")).thenReturn(oldVersion);
            handler = new MigrationHandler(config,
                    new PluginDescriptionFile("Stonks", currentVersion, "me.geraldr12.Stonks"),
                    directory.toFile(), logger, companies, players, signs);
        }

        void verifyNoMigrationOrWarning() {
            verify(logger, never()).warning(anyString());
            verify(logger, never()).severe(anyString());
            verifyNoInteractions(companies, players, signs);
            assertFalse(Files.exists(directory.resolve("migration")));
        }
    }
}
