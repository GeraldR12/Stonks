package me.geraldr12.ui.items;

import me.geraldr12.Stonks;
import me.geraldr12.commands.ToggleNotificationCommand;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.enums.NotificationType;
import me.geraldr12.utils.Messages;
// REMOVE dev-command imports
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AbstractItem;

import java.text.MessageFormat;
import java.util.UUID;

public class NotificationToggleItem extends AbstractItem {

    private final Stonks plugin;
    private final PlayersService playersService;
    private final Messages messages;
    private final NotificationType notificationType;
    private final UUID playerId;

    public NotificationToggleItem(Stonks plugin, PlayersService playersService, Messages messages, NotificationType notificationType, UUID playerId) {
        this.plugin = plugin;
        this.playersService = playersService;
        this.messages = messages;
        this.notificationType = notificationType;
        this.playerId = playerId;
    }

    @Override
    public ItemProvider getItemProvider() {
        boolean isEnabled = playersService.hasNotificationEnabled(playerId, notificationType);
        Material material = isEnabled ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        String status = isEnabled ? messages.getEnabledString() : messages.getDisabledString();

        return new ItemBuilder(material)
                .setDisplayName(ChatColor.GOLD + messages.getMessageByKey(notificationType.getMessageKey()))
                .addLoreLines(MessageFormat.format(messages.getUiNotificationsToggleStatus(), status));
    }

    @Override
    public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent inventoryClickEvent) {

        // DIRECT EXECUTION: Call the converted ToggleNotificationCommand directly
        ToggleNotificationCommand toggleLogic = new ToggleNotificationCommand(plugin);
        toggleLogic.onCommand(player, new String[]{notificationType.name()});

        // Update the item to reflect the new state in the GUI
        notifyWindows();
    }
}