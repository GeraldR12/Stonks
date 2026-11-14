package me.geraldr12.ui.guis;

import java.util.Arrays;
import java.util.List;
import me.geraldr12.enums.NotificationType;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.NotificationToggleItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class NotificationsGui extends AbstractPluginGui {
    public NotificationsGui(Player player, GuiManager guiManager, Messages messages) {
        super(messages.getUiNotificationsTitle(), "stonks.ui.notifications", player, guiManager, messages);
    }

    @Override
    public PagedLayout build() {
        List<MenuButton> items = Arrays.stream(NotificationType.values())
                .<MenuButton>map(type -> new NotificationToggleItem(guiManager.getPlugin(),
                        guiManager.getPlayersService(), messages, type, player.getUniqueId())).toList();
        return new PagedLayout(new String[]{
                "# # # # # # # # #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# # # < - > # # #"
        }, controls(Material.GRAY_STAINED_GLASS_PANE, ""), items);
    }
}
