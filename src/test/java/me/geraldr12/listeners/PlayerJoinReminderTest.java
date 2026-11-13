package me.geraldr12.listeners;

import me.geraldr12.Stonks;
import me.geraldr12.api.services.AutoUpdateService;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerJoinReminderTest {
    private static final String[] NEW_KEY_ON = {"Stonks.Updates.Reminder", "true"};
    private static final String[] NEW_KEY_OFF = {"Stonks.Updates.Reminder", "false"};
    private static final String[] LEGACY_ON = {"Stonks.Updates.Remainder", "true"};
    private static final String[] LEGACY_OFF = {"Stonks.Updates.Remainder", "false"};

    @Test
    void freshInstallWithDefaultsEnablesReminder() {
        assertReminder(null, true, true);
    }

    @Test
    void correctedKeyEnablesReminder() {
        assertReminder(NEW_KEY_ON, true, true);
    }

    @Test
    void correctedKeyDisabledSkipsReminder() {
        assertReminder(NEW_KEY_OFF, true, false);
    }

    @Test
    void legacyMisspelledKeyStillEnablesReminder() {
        assertReminder(LEGACY_ON, true, true);
    }

    @Test
    void legacyDisabledKeyWinsOverCopiedDefault() {
        // startup copies "Reminder: true" from the jar into an old file that only had "Remainder: false"
        assertReminder(LEGACY_OFF, true, false);
    }

    @Test
    void reminderStillRequiresAdminPermission() {
        assertReminder(NEW_KEY_ON, false, false);
    }

    @Test
    void defaultConfigUsesCorrectedKey() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(config.contains("    Reminder: true"));
        assertFalse(config.contains("    Remainder:"));
    }

    /** Same shape as the server: file values plus the jar default "Reminder: true", copied in at startup. */
    private FileConfiguration serverConfig(String[] fileEntry) {
        MemoryConfiguration defaults = new MemoryConfiguration();
        defaults.set("Stonks.Updates.Reminder", true);
        FileConfiguration config = new FileConfiguration() {
            @Override
            public String saveToString() {
                return "";
            }

            @Override
            public void loadFromString(String contents) {
            }

            @Override
            protected String buildHeader() {
                return "";
            }
        };
        if (fileEntry != null) {
            config.set(fileEntry[0], Boolean.parseBoolean(fileEntry[1]));
        }
        config.setDefaults(defaults);
        config.options().copyDefaults(true);
        return config;
    }

    private void assertReminder(String[] fileEntry, boolean admin, boolean expected) {
        Stonks plugin = mock(Stonks.class);
        when(plugin.getConfig()).thenReturn(serverConfig(fileEntry));
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("blockstreet.admin.reload")).thenReturn(admin);
        AutoUpdateService updates = mock(AutoUpdateService.class);
        when(updates.isUpdateAvailable()).thenReturn(CompletableFuture.completedFuture(false));
        PlayersService players = mock(PlayersService.class);
        CompaniesService companies = mock(CompaniesService.class);
        when(companies.getAllCompanies()).thenReturn(List.of());
        PlayerJoinListener listener = new PlayerJoinListener(plugin, mock(Messages.class), updates, players, companies);

        listener.onPlayerJoinEvent(new PlayerJoinEvent(player, ""));

        verify(updates, times(expected ? 1 : 0)).isUpdateAvailable();
        verify(players).cleanUpInvestments(playerId, List.of());
    }
}
