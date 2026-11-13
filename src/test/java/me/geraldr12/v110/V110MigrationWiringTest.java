package me.geraldr12.v110;

import me.geraldr12.MigrationHandler;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.data.repositories.implementations.SignsRepository;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.v110.destination.CompanyIdCounter;
import me.geraldr12.v110.MigrationState.MigrationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class V110MigrationWiringTest {

    @TempDir
    Path directory;

    private MigrationHandler handler(String oldVersion, String currentVersion, CompaniesService companies,
                                     PlayersService players, SignsService signs) {
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getString("Stonks.Version")).thenReturn(oldVersion);
        PluginDescriptionFile description = new PluginDescriptionFile("Stonks", currentVersion, "me.geraldr12.Stonks");
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return new MigrationHandler(config, description, directory.toFile(), logger, companies, players, signs);
    }

    @Test
    void currentVersionNeverRunsTheMigration() throws IOException {
        Files.writeString(directory.resolve("companies.yml"), "Companies: {}\n");
        CompaniesService companies = mock(CompaniesService.class);
        PlayersService players = mock(PlayersService.class);
        SignsService signs = mock(SignsService.class);

        handler("1.0.0", "2.9.1", companies, players, signs).checkMigrations();

        verifyNoInteractions(companies, players, signs);
        assertFalse(Files.exists(directory.resolve("migration")));
        assertTrue(Files.exists(directory.resolve("companies.yml")));
    }

    @Test
    void version200RunsTheCoordinatorThroughProductionAdapters() throws Exception {
        CompaniesService companies = mock(CompaniesService.class);
        doReturn(mock(CompaniesRepository.class)).when(companies).getRepository();
        PlayersService players = mock(PlayersService.class);
        doReturn(mock(PlayersRepository.class)).when(players).getRepository();
        SignsService signs = mock(SignsService.class);
        Field repository = SignsService.class.getField("repository");
        assertEquals(SignsRepository.class, repository.getType());

        handler("1.0.0", "2.0.0", companies, players, signs).checkMigrations();

        assertTrue(Files.readString(directory.resolve("migration/v110/state.json")).contains("\"phase\":\"DONE\""));
    }

    @Test
    void counterIsNotCreatedWhenAbsent() {
        Path file = directory.resolve("companies/data.yml");

        CompanyIdCounter.raiseTo(file.toFile(), 10, MigrationFileOps.DEFAULT);

        assertFalse(Files.exists(file));
    }

    @Test
    void counterIsRaisedWhenLower() throws IOException {
        Path file = write("nextId: 3\n");

        CompanyIdCounter.raiseTo(file.toFile(), 10, MigrationFileOps.DEFAULT);

        assertTrue(Files.readString(file).contains("nextId: 10"));
    }

    @Test
    void counterIsNeverLowered() throws IOException {
        Path file = write("nextId: 50\n");

        CompanyIdCounter.raiseTo(file.toFile(), 10, MigrationFileOps.DEFAULT);

        assertEquals("nextId: 50\n", Files.readString(file));
    }

    @Test
    void counterWithoutValueFails() throws IOException {
        Path file = write("other: 1\n");

        assertThrows(MigrationException.class, () -> CompanyIdCounter.raiseTo(file.toFile(), 10, MigrationFileOps.DEFAULT));

        assertEquals("other: 1\n", Files.readString(file));
    }

    @Test
    void unreadableCounterFails() throws IOException {
        Path file = write("nextId: [unclosed\n");

        assertThrows(MigrationException.class, () -> CompanyIdCounter.raiseTo(file.toFile(), 10, MigrationFileOps.DEFAULT));
    }

    private Path write(String content) throws IOException {
        Path file = directory.resolve("companies/data.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
