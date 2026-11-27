package me.geraldr12.ui.menu;

import java.util.function.Consumer;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.geraldr12.ui.GuiManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

public class MenuListener implements Listener {
    private final GuiManager manager;
    private final Consumer<Runnable> nextTick;
    private final Map<UUID, Input> inputs = new ConcurrentHashMap<>();

    private record Input(long started, Consumer<String> response) {}

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
                    || holder.getButton(slot) != button || !holder.getMenu().canOpen()) return;
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

    public void expectInput(Player player, Consumer<String> response) {
        inputs.put(player.getUniqueId(), new Input(System.nanoTime(), response));
    }

    public void clearInput(Player player) {
        inputs.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMenuInput(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Input input = inputs.get(id);
        if (input == null || !inputs.remove(id, input)) return;
        if (System.nanoTime() - input.started() >= Duration.ofSeconds(60).toNanos()) return;
        event.setCancelled(true);
        String message = event.getMessage().trim();
        nextTick.accept(() -> {
            if (player.isOnline()) input.response().accept(message);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        clearInput(player);
        manager.onQuit(player);
    }
}
