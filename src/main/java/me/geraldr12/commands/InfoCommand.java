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

        String currentVersion = autoUpdateService.getCurrentVersion();
        autoUpdateService.getLatestRelease().thenAccept(latestReleaseEntity -> {
            String latestVersion = latestReleaseEntity == null ? null : latestReleaseEntity.getReleaseVersion();
            boolean isUpdateAvailable = latestVersion != null
                    && new org.apache.maven.artifact.versioning.ComparableVersion(currentVersion)
                    .compareTo(new org.apache.maven.artifact.versioning.ComparableVersion(
                            latestVersion.replace("v", ""))) < 0;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled() || !player.isOnline()) {
                    return;
                }
                player.sendMessage(plugin.getMessages().getPluginHeader());
                player.sendMessage("");
                player.sendMessage(ChatColor.GREEN + "Current Version: " + ChatColor.GRAY + currentVersion);
                player.sendMessage(ChatColor.GREEN + "Last Version: " + ChatColor.GRAY
                        + (latestVersion == null ? "unavailable" : latestVersion));
                player.sendMessage("");

                if (isUpdateAvailable) {
                    player.sendMessage(ChatColor.GRAY + "New version available!");
                    player.sendMessage(ChatColor.GRAY + "Download it on the official plugin page.");
                } else if (latestVersion != null) {
                    player.sendMessage(ChatColor.GRAY + "Your plugin is up to date.");
                }

                player.sendMessage("");
                player.sendMessage(ChatColor.GRAY + "Plugin created by: " + ChatColor.GREEN + "VenaceGR");
                player.sendMessage(messages.getPluginFooter());
            });
        }).exceptionally(error -> {
            plugin.getLogger().warning("Unable to load release information for /invest info: "
                    + error.getClass().getSimpleName());
            return null;
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}