package me.geraldr12.ui.items;

import me.geraldr12.ui.GuiManager;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AbstractItem;

public class CreateCompanyButtonItem extends AbstractItem {

    private final GuiManager guiManager;
    private final Messages messages;

    public CreateCompanyButtonItem(GuiManager guiManager, Messages messages) {
        this.guiManager = guiManager;
        this.messages = messages;
    }

    @Override
    public ItemProvider getItemProvider() {
        return new ItemBuilder(Material.ANVIL)
                .setDisplayName("§a§lCreate New Company")
                .addLoreLines("§7Click to start creating", "§7your own stock company!");
    }

    @Override
    public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent event) {
        player.closeInventory();
        // Prompt player to use the command or open a sub-GUI
        player.sendMessage(messages.getPluginPrefix() + "§7Use §e/invest company create <name> <risk> <shares> <price> §7to create your company!");
    }
}