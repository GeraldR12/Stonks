package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.api.services.AutoUpdateService;
import me.geraldr12.utils.Messages;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Info Command
 * Converted to standard Bukkit API via SubCommand interface.
 * Updated author to VenaceGR.
 */
public class InfoCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public InfoCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        AutoUpdateService autoUpdateService = plugin.getAutoUpdateService();

        autoUpdateService.getLatestRelease().thenAcceptAsync(latestReleaseEntity -> {

            boolean isUpdateAvailable = autoUpdateService.isUpdateAvailable().join();
            String currentVersion = autoUpdateService.getCurrentVersion();

            player.sendMessage(messages.getPluginHeader());
            player.sendMessage("");
            player.sendMessage(ChatColor.GREEN + "Current Version: " + ChatColor.GRAY + currentVersion);
            player.sendMessage(ChatColor.GREEN + "Last Version: " + ChatColor.GRAY + latestReleaseEntity.getReleaseVersion());
            player.sendMessage("");

            if (isUpdateAvailable) {
                player.sendMessage(ChatColor.GRAY + "New version available!");
                // Link placeholder maintained as per original logic but updated contextually
                player.sendMessage(ChatColor.GRAY + "Download it on the official plugin page.");
            } else {
                player.sendMessage(ChatColor.GRAY + "Your plugin is up to date.");
            }

            player.sendMessage("");
            // Updated author name to VenaceGR
            player.sendMessage(ChatColor.GRAY + "Plugin created by: " + ChatColor.GREEN + "VenaceGR");
            player.sendMessage(messages.getPluginFooter());

        });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}