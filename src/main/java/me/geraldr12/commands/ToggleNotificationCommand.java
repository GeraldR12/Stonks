package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.enums.NotificationType;
import me.geraldr12.utils.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Toggle Notifications Command
 * Converted to standard Bukkit API via SubCommand interface.
 */
public class ToggleNotificationCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public ToggleNotificationCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        PlayersService playersService = plugin.getPlayersService();

        // Check permission manually
        if (!player.hasPermission("blockstreet.command.notification")) {
            player.sendMessage(messages.getPluginPrefix() + messages.getNoPermission());
            return true;
        }

        // Validate argument length: /invest notification <type>
        if (args.length < 1) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest notification <type>");
            return true;
        }

        try {
            // Replaces the custom NotificationTypeArgumentParser
            NotificationType notificationType = NotificationType.valueOf(args[0].toUpperCase());

            playersService.toggleNotification(player.getUniqueId(), notificationType);

            String status = playersService.hasNotificationEnabled(player.getUniqueId(), notificationType)
                    ? messages.getEnabledString()
                    : messages.getDisabledString();

            String notificationName = messages.getMessageByKey(notificationType.getMessageKey());

            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(
                    messages.getNotificationToggled(),
                    status,
                    notificationName
            ));

        } catch (IllegalArgumentException e) {
            player.sendMessage(messages.getPluginPrefix() + "Error: Invalid notification type.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Arrays.stream(NotificationType.values())
                    .map(Enum::name)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}