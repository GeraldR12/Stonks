package me.geraldr12.ui.menu;

import java.util.function.Consumer;
import me.geraldr12.ui.GuiManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public class MenuListener implements Listener {
    private final GuiManager manager;
    private final Consumer<Runnable> nextTick;

    public MenuListener(GuiManager manager, Consumer<Runnable> nextTick) {
        this.manager = manager;
        this.nextTick = nextTick;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize() || !(event.getWhoClicked() instanceof Player player)) return;
        MenuButton button = holder.getButton(slot);
        if (button == null || button.getAction() == null) return;
        var click = event.getClick();
        nextTick.accept(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != holder
                    || holder.getButton(slot) != button) return;
            button.getAction().accept(player, click);
            if (player.getOpenInventory().getTopInventory().getHolder() == holder) holder.refresh();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder holder
                && event.getPlayer() instanceof Player player) manager.onClose(player, holder);
    }
}
