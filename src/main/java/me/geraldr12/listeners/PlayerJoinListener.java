package me.geraldr12.listeners;

import com.google.inject.Inject;
import me.geraldr12.Stonks;
import me.geraldr12.api.services.AutoUpdateService;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.utils.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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

        if (stonks.getConfig().getBoolean("Stonks.Updates.Reminder") && (joinedPlayer.isOp() || joinedPlayer.hasPermission("blockstreet.admin.*"))) {
            autoUpdateService.isUpdateAvailable().thenAcceptAsync((isUpdateAvailable) -> {
                if (isUpdateAvailable) {
                    stonks.getServer().getScheduler().runTaskLater(stonks, () -> {
                        joinedPlayer.sendMessage(messages.getPluginPrefix() + messages.getNewVersionAvailable());
                    }, 5000);
                }
            });
        }

        // Clean up investments for the player (remove investments in deleted companies)
        playersService.cleanUpInvestments(joinedPlayer.getUniqueId(), companiesService.getAllCompanies());
    }

}
