package me.geraldr12.ui.items;

import java.util.List;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.PluginGuiType;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;

public class NotificationSettingsButtonItem extends MenuButton {
    public NotificationSettingsButtonItem(GuiManager guiManager, Messages messages) {
        super(new MenuIcon(Material.BELL, messages.getUiNotificationsItemTitle(),
                List.of(messages.getUiNotificationsItemDescription())),
                (player, click) -> guiManager.navigate(player, PluginGuiType.NOTIFICATIONS_GUI));
    }
}
