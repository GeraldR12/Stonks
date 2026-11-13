package me.geraldr12.v110;

import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.data.repositories.implementations.SignsRepository;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.data.sources.yml.implementations.PlayersYml;
import me.geraldr12.data.sources.yml.implementations.SignsYml;
import me.geraldr12.v110.destination.ProductionPlayerDestination;
import me.geraldr12.v110.destination.ProductionSignDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionDestinationReadSafetyTest {
    @TempDir Path directory;

    private ProductionPlayerDestination players(PlayersService service) throws Exception {
        try {
            return ProductionPlayerDestination.class.getConstructor(PlayersService.class, File.class)
                    .newInstance(service, directory.toFile());
        } catch (NoSuchMethodException e) {
            return ProductionPlayerDestination.class.getConstructor(PlayersService.class).newInstance(service);
        }
    }

    private ProductionSignDestination signs(SignsService service) throws Exception {
        try {
            return ProductionSignDestination.class.getConstructor(SignsService.class, File.class)
                    .newInstance(service, directory.toFile());
        } catch (NoSuchMethodException e) {
            return ProductionSignDestination.class.getConstructor(SignsService.class).newInstance(service);
        }
    }

    @Test
    void unreadablePlayerIsNotAbsentOrOverwritten() throws Exception {
        UUID id = UUID.nameUUIDFromBytes(new byte[]{1});
        String relative = "players/" + id + ".yml";
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "investments: [broken");
        PlayersYml source = mock(PlayersYml.class);
        when(source.exists(anyString())).thenReturn(true);
        PlayersService service = mock(PlayersService.class);
        doReturn(new PlayersRepository(source)).when(service).getRepository();
        ProductionPlayerDestination destination = players(service);

        assertThrows(MigrationState.MigrationException.class, () -> destination.findById(id));
        assertEquals("investments: [broken", Files.readString(file));
        verify(source, never()).save(anyString(), any());
    }

    @Test
    void unreadableSignIsNotAbsentOrOverwritten() throws Exception {
        Path file = directory.resolve("signs/5.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "location: [broken");
        SignsYml source = mock(SignsYml.class);
        when(source.exists(anyString())).thenReturn(true);
        SignsService service = new SignsService(new SignsRepository(source), null, null);
        ProductionSignDestination destination = signs(service);

        assertThrows(MigrationState.MigrationException.class, () -> destination.findById(5));
        assertEquals("location: [broken", Files.readString(file));
        verify(source, never()).save(anyString(), any());
    }

    @Test
    void trulyAbsentPlayerRemainsAbsent() throws Exception {
        PlayersService service = mock(PlayersService.class);
        doReturn(mock(PlayersRepository.class)).when(service).getRepository();
        assertTrue(players(service).findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void trulyAbsentSignRemainsAbsent() throws Exception {
        SignsService service = new SignsService(mock(SignsRepository.class), null, null);
        assertTrue(signs(service).findById(5).isEmpty());
    }
}
