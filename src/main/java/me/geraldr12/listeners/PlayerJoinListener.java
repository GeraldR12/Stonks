package me.geraldr12.listeners;

import com.google.inject.Inject;
import me.geraldr12.Stonks;
import me.geraldr12.api.services.AutoUpdateService;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;

import java.util.UUID;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final Stonks stonks;
    private final Messages messages;
    private final AutoUpdateService autoUpdateService;
    private final PlayersService playersService;
    private final CompaniesService companiesService;

    @Inject
    public PlayerJoinListener(Stonks stonks, Messages messages, AutoUpdateService autoUpdateService,
                              PlayersService playersService, CompaniesService companiesService) {
        this.stonks = stonks;
        this.messages = messages;
        this.autoUpdateService = autoUpdateService;
        this.playersService = playersService;
        this.companiesService = companiesService;
    }

    @EventHandler
    public void onPlayerJoinEvent(PlayerJoinEvent e) {
        Player joinedPlayer = e.getPlayer();
        UUID playerId = joinedPlayer.getUniqueId();
        playersService.rememberName(playerId, joinedPlayer.getName());

        if (isReminderEnabled(stonks.getConfig())
                && (joinedPlayer.isOp() || joinedPlayer.hasPermission("blockstreet.admin.reload"))) {
            autoUpdateService.isUpdateAvailable().thenAccept(isUpdateAvailable -> {
                if (isUpdateAvailable && !autoUpdateService.isShuttingDown()) {
                    stonks.getServer().getScheduler().runTaskLater(stonks, () -> {
                        Player currentPlayer = stonks.getServer().getPlayer(playerId);
                        if (stonks.isEnabled() && currentPlayer != null && currentPlayer.isOnline()) {
                            currentPlayer.sendMessage(messages.getPluginPrefix() + messages.getNewVersionAvailable());
                        }
                    }, 100L);
                }
            }).exceptionally(error -> {
                stonks.getLogger().warning("Unable to check for update reminder: " + error.getClass().getSimpleName());
                return null;
            });
        }

        // Clean up investments for the player (remove investments in deleted companies)
        playersService.cleanUpInvestments(joinedPlayer.getUniqueId(), companiesService.getAllCompanies());
    }

    /**
     * Older config files used the misspelled key "Remainder". Startup copies the new default
     * "Reminder: true" into those files, so an explicit legacy "Remainder: false" must still win.
     */
    static boolean isReminderEnabled(FileConfiguration config) {
        boolean reminder = config.getBoolean("Stonks.Updates.Reminder", true);
        boolean legacyAllows = !config.isSet("Stonks.Updates.Remainder")
                || config.getBoolean("Stonks.Updates.Remainder");
        return reminder && legacyAllows;
    }

}
