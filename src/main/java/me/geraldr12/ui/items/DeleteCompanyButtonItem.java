package me.geraldr12.ui.items;

import me.geraldr12.Stonks;
import me.geraldr12.commands.DeleteCommand;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AbstractItem;

public class DeleteCompanyButtonItem extends AbstractItem {

    private final Stonks plugin;
    private final long companyId;

    public DeleteCompanyButtonItem(Stonks plugin, long companyId) {
        this.plugin = plugin;
        this.companyId = companyId;
    }

    @Override
    public ItemProvider getItemProvider() {
        return new ItemBuilder(Material.BARRIER)
                .setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "DELETE COMPANY")
                .addLoreLines(
                        ChatColor.GRAY + "Click to permanently delete",
                        ChatColor.GRAY + "this company and sell all shares.",
                        "",
                        ChatColor.DARK_RED + "WARNING: THIS CANNOT BE UNDONE!"
                );
    }

    @Override
    public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent event) {
        player.closeInventory();
        // Call your existing DeleteCommand logic
        DeleteCommand deleteCmd = new DeleteCommand(plugin);
        deleteCmd.onCommand(player, new String[]{String.valueOf(companyId)});
    }
}