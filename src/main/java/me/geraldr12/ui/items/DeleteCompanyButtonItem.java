package me.geraldr12.ui.items;

import java.util.List;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;

public class DeleteCompanyButtonItem extends MenuButton {
    public DeleteCompanyButtonItem(GuiManager guiManager, Messages messages, long companyId) {
        super(new MenuIcon(Material.BARRIER, messages.getMessageByKey("uiDeleteButton"),
                List.of(messages.getMessageByKey("uiDeleteDescription"),
                        messages.getMessageByKey("uiDeleteWarning"))),
                (player, click) -> guiManager.openConfirmDeleteMenu(player, companyId));
    }
}
