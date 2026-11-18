package me.geraldr12.ui.items;

import java.util.List;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;

public class CreateCompanyButtonItem extends MenuButton {
    public CreateCompanyButtonItem(GuiManager guiManager, Messages messages) {
        super(new MenuIcon(Material.ANVIL, messages.getMessageByKey("uiCreateButton"), List.of()),
                (player, click) -> guiManager.openCompanyCreation(player));
    }
}
