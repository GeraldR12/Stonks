package me.geraldr12.ui.guis;

import me.geraldr12.enums.NotificationType;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.NavigateBackItem;
import me.geraldr12.ui.items.NextPageItem;
import me.geraldr12.ui.items.NotificationToggleItem;
import me.geraldr12.ui.items.PreviousPageItem;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.gui.structure.Markers;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.SimpleItem;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class NotificationsGui extends AbstractPluginGui {
    public NotificationsGui(Player player, GuiManager guiManager, Messages messages) {
        super(messages.getUiNotificationsTitle(), "blockstreet.ui.notifications", player, guiManager, messages);
    }

    @Override
    public Gui build() {
        List<Item> notificationItems = Arrays.stream(NotificationType.values())
                .map(notificationType -> new NotificationToggleItem(
                        guiManager.getPlugin(),
                        guiManager.getPlayersService(),
                        messages,
                        notificationType,
                        player.getUniqueId()
                ))
                .collect(Collectors.toList());

        return PagedGui.items()
                .setStructure(
                        "# # # # # # # # #",
                        "# x x x x x x x #",
                        "# x x x x x x x #",
                        "# # # < - > # # #")
                .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL) // where paged items should be put
                .addIngredient('#', new SimpleItem(new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).setDisplayName("")))
                .addIngredient('-', new NavigateBackItem(guiManager, messages))
                .addIngredient('<', new PreviousPageItem(messages))
                .addIngredient('>', new NextPageItem(messages))
                .setContent(notificationItems)
                .build();
    }
}
