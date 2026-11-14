package me.geraldr12.ui.items;

import java.util.List;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;

public class NavigateBackItem extends MenuButton {
    public NavigateBackItem(GuiManager guiManager, Messages messages) {
        this(guiManager, messages, false);
    }

    public NavigateBackItem(GuiManager guiManager, Messages messages, boolean previousScreen) {
        super(new MenuIcon(Material.REDSTONE_TORCH, messages.getUiNavigateBack(), List.of()), (player, click) -> {
            if (previousScreen) guiManager.navigateBack(player);
            else guiManager.navigateMainMenu(player);
        });
    }
}
