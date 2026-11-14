package me.geraldr12.ui.items;

import java.util.List;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;

public class CreateCompanyButtonItem extends MenuButton {
    public CreateCompanyButtonItem(GuiManager guiManager, Messages messages) {
        super(new MenuIcon(Material.ANVIL, "§a§lCreate New Company",
                List.of("§7Click to start creating", "§7your own stock company!")), (player, click) -> {
            guiManager.close(player);
            player.sendMessage(messages.getPluginPrefix()
                    + "§7Use §e/invest company create <name> <risk> <shares> <price> §7to create your company!");
        });
    }
}
