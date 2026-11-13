package me.geraldr12.ui.items;

import java.text.MessageFormat;
import java.util.List;
import java.util.UUID;
import me.geraldr12.Stonks;
import me.geraldr12.commands.ToggleNotificationCommand;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.enums.NotificationType;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.Messages;
import org.bukkit.ChatColor;
import org.bukkit.Material;

public class NotificationToggleItem extends MenuButton {
    public NotificationToggleItem(Stonks plugin, PlayersService playersService, Messages messages,
                                  NotificationType notificationType, UUID playerId) {
        super(icon(playersService, messages, notificationType, playerId), (player, click) ->
                new ToggleNotificationCommand(plugin).onCommand(player, new String[]{notificationType.name()}));
    }

    private static MenuIcon icon(PlayersService playersService, Messages messages, NotificationType type, UUID playerId) {
        boolean enabled = playersService.hasNotificationEnabled(playerId, type);
        return new MenuIcon(enabled ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                ChatColor.GOLD + messages.getMessageByKey(type.getMessageKey()),
                List.of(MessageFormat.format(messages.getUiNotificationsToggleStatus(),
                        enabled ? messages.getEnabledString() : messages.getDisabledString())));
    }
}
