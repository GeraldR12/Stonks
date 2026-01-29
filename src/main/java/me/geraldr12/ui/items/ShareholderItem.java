package me.geraldr12.ui.items;

import me.geraldr12.data.dao.InvestmentDao;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AbstractItem;
import xyz.xenondevs.invui.item.ItemProvider;

import java.util.UUID;

public class ShareholderItem extends AbstractItem {

    private final UUID shareholderUuid;
    private final int sharesOwned;

    public ShareholderItem(UUID shareholderUuid, int sharesOwned) {
        this.shareholderUuid = shareholderUuid;
        this.sharesOwned = sharesOwned;
    }

    // Inside ShareholderItem.java
    @Override
    public ItemProvider getItemProvider() {
        org.bukkit.OfflinePlayer offlinePlayer = org.bukkit.Bukkit.getOfflinePlayer(shareholderUuid);

        // 1. Create a standard Bukkit ItemStack
        org.bukkit.inventory.ItemStack skull = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.ItemMeta meta = skull.getItemMeta();

        if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
            org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) meta;

            // 2. Set the owner, name, and lore using standard Bukkit methods
            skullMeta.setOwningPlayer(offlinePlayer);
            skullMeta.setDisplayName(org.bukkit.ChatColor.YELLOW + (offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown Investor"));

            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(org.bukkit.ChatColor.GRAY + "Shares Owned: " + org.bukkit.ChatColor.WHITE + sharesOwned);
            skullMeta.setLore(lore);

            // 3. Apply the meta back to the stack
            skull.setItemMeta(skullMeta);
        }

        // 4. Return an ItemBuilder wrapping the finished ItemStack
        return new ItemBuilder(skull);
    }
    @Override
    public void handleClick(@NotNull org.bukkit.event.inventory.ClickType clickType, @NotNull org.bukkit.entity.Player player, @NotNull org.bukkit.event.inventory.InventoryClickEvent event) {
        // Leave empty if you don't want anything to happen when clicking a shareholder
    }
}