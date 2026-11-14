package me.geraldr12.ui.items;

import java.util.List;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.PluginGuiType;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;

public class PortfolioButtonItem extends MenuButton {
    public PortfolioButtonItem(GuiManager guiManager, Messages messages) {
        super(new MenuIcon(Material.DIAMOND, messages.getUiCheckPortfolioButtonTitle(),
                List.of(messages.getUiCheckPortfolioButtonDescription())),
                (player, click) -> guiManager.navigate(player, PluginGuiType.PORTFOLIO_GUI));
    }
}
