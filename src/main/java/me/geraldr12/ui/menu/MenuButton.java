package me.geraldr12.ui.menu;

import java.util.Objects;
import java.util.function.BiConsumer;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

@Getter
public class MenuButton {
    private final MenuIcon icon;
    private final BiConsumer<Player, ClickType> action;

    public MenuButton(MenuIcon icon, BiConsumer<Player, ClickType> action) {
        this.icon = Objects.requireNonNull(icon);
        this.action = action;
    }

    public ItemStack createItemStack() {
        ItemStack item = new ItemStack(icon.material(), icon.amount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(icon.name());
            meta.setLore(icon.lore());
            item.setItemMeta(meta);
        }
        return item;
    }
}
