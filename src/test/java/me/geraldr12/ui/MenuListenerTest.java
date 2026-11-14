package me.geraldr12.ui;

import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuHolder;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.MenuListener;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenuListenerTest {
    private final GuiManager manager = mock(GuiManager.class);
    private final MenuListener listener = new MenuListener(manager, Runnable::run);
    private final Inventory top = mock(Inventory.class);
    private final InventoryView view = mock(InventoryView.class);
    private final MenuHolder holder = mock(MenuHolder.class);
    private final Player player = mock(Player.class);
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        when(view.getTopInventory()).thenReturn(top);
        when(top.getHolder()).thenReturn(holder);
        when(top.getSize()).thenReturn(27);
        when(player.getOpenInventory()).thenReturn(view);
        when(holder.getButton(10)).thenReturn(new MenuButton(new MenuIcon(Material.STONE, "button", List.of()),
                (who, click) -> { assertSame(player, who); calls.incrementAndGet(); }));
    }

    @Test
    void topClickIsCancelledAndExecutesActionAndRefreshes() {
        InventoryClickEvent event = click(10, ClickType.RIGHT);
        listener.onClick(event);
        verify(event).setCancelled(true);
        assertEquals(1, calls.get());
        verify(holder).refresh();
    }

    @Test
    void everyClickTypeIsCancelledIncludingPlayerInventoryAndOutside() {
        for (ClickType type : ClickType.values()) {
            for (int slot : new int[]{-999, 28, 50}) {
                InventoryClickEvent event = click(slot, type);
                listener.onClick(event);
                verify(event).setCancelled(true);
            }
        }
        assertEquals(0, calls.get());
    }

    @Test
    void deferredClickDoesNotRunAfterMenuHasClosed() {
        AtomicInteger scheduled = new AtomicInteger();
        MenuListener deferred = new MenuListener(manager, task -> {
            scheduled.incrementAndGet();
            when(top.getHolder()).thenReturn(mock(InventoryHolder.class));
            task.run();
        });
        deferred.onClick(click(10, ClickType.LEFT));
        assertEquals(1, scheduled.get());
        assertEquals(0, calls.get());
    }

    @Test
    void dragIsCancelled() {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(view);
        listener.onDrag(event);
        verify(event).setCancelled(true);
    }

    @Test
    void unrelatedInventoryIsUntouched() {
        when(top.getHolder()).thenReturn(mock(InventoryHolder.class));
        InventoryClickEvent click = click(10, ClickType.SHIFT_LEFT);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        listener.onClick(click);
        listener.onDrag(drag);
        verify(click, never()).setCancelled(anyBoolean());
        verify(drag, never()).setCancelled(anyBoolean());
        assertEquals(0, calls.get());
    }

    @Test
    void closeNotifiesManagerWithTheExactHolder() {
        // getPlayer()/getInventory() sao finais no Bukkit: evento real sobre a view simulada
        when(view.getTopInventory()).thenReturn(top);
        when(view.getPlayer()).thenReturn(player);
        InventoryCloseEvent event = new InventoryCloseEvent(view);
        listener.onClose(event);
        verify(manager).onClose(player, holder);
    }

    private InventoryClickEvent click(int rawSlot, ClickType type) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(rawSlot);
        when(event.getClick()).thenReturn(type);
        return event;
    }
}
