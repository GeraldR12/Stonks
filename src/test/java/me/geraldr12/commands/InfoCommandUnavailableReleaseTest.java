package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.api.entities.PluginReleaseEntity;
import me.geraldr12.api.services.AutoUpdateService;
import me.geraldr12.utils.Messages;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InfoCommandUnavailableReleaseTest {
    private final Stonks plugin = mock(Stonks.class);
    private final Player player = mock(Player.class);
    private final AutoUpdateService updates = mock(AutoUpdateService.class);
    private final Logger logger = mock(Logger.class);
    private final List<Runnable> scheduled = new ArrayList<>();

    private void command(PluginReleaseEntity release) {
        Messages messages = mock(Messages.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getMessages()).thenReturn(messages);
        when(messages.getPluginHeader()).thenReturn("HEADER");
        when(messages.getPluginFooter()).thenReturn("FOOTER");
        when(plugin.getAutoUpdateService()).thenReturn(updates);
        when(updates.getCurrentVersion()).thenReturn("2.9.1");
        when(updates.getLatestRelease()).thenReturn(CompletableFuture.completedFuture(release));
        when(plugin.getLogger()).thenReturn(logger);
        try {
            var serverField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(plugin, server);
            var enabledField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("isEnabled");
            enabledField.setAccessible(true);
            enabledField.setBoolean(plugin, true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.isOnline()).thenReturn(true);
        doAnswer(invocation -> { scheduled.add(invocation.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        assertTrue(new InfoCommand(plugin).onCommand(player, new String[0]));
    }

    private List<String> messages() {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(player, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream().map(net.md_5.bungee.api.ChatColor::stripColor).toList();
    }

    private void unavailable(PluginReleaseEntity release) {
        command(release);
        verify(player, never()).sendMessage(anyString());
        assertEquals(1, scheduled.size(), "response is scheduled on main thread");
        scheduled.get(0).run();
        List<String> sent = messages();
        assertEquals("HEADER", sent.get(0));
        assertTrue(sent.contains("Current Version: 2.9.1"));
        assertTrue(sent.contains("Last Version: unavailable"));
        assertTrue(sent.contains("Plugin created by: VenaceGR"));
        assertEquals("FOOTER", sent.get(sent.size() - 1));
        assertFalse(sent.contains("Your plugin is up to date."));
        assertFalse(sent.contains("New version available!"));
        verify(logger, never()).warning(anyString());
    }

    @Test void nullReleaseStillRespondsWithoutWarning() { unavailable(null); }
    @Test void nullReleaseVersionStillRespondsWithoutWarning() { unavailable(PluginReleaseEntity.builder().build()); }

    @Test
    void newerReleaseKeepsUpdateMessage() {
        command(PluginReleaseEntity.builder().releaseVersion("v3.0.0").build());
        scheduled.get(0).run();
        assertTrue(messages().contains("New version available!"));
    }

    @Test
    void currentReleaseKeepsUpToDateMessage() {
        command(PluginReleaseEntity.builder().releaseVersion("v2.9.1").build());
        scheduled.get(0).run();
        assertTrue(messages().contains("Your plugin is up to date."));
    }

    @Test
    void failedReleaseFutureStillLogsWarning() {
        command(PluginReleaseEntity.builder().releaseVersion("v2.9.1").build());
        scheduled.clear();
        when(updates.getLatestRelease()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("failed")));

        assertTrue(new InfoCommand(plugin).onCommand(player, new String[0]));

        assertTrue(scheduled.isEmpty());
        verify(logger).warning(startsWith("Unable to load release information for /invest info:"));
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void unavailableResponseStillHonorsOfflineGuard() {
        command(null);
        when(player.isOnline()).thenReturn(false);
        assertEquals(1, scheduled.size());
        scheduled.get(0).run();
        verify(player, never()).sendMessage(anyString());
        verify(logger, never()).warning(anyString());
    }
}
