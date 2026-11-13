package me.geraldr12.ui.menu;

import java.util.Map;
import lombok.Getter;
import me.geraldr12.ui.AbstractPluginGui;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

@Getter
public class MenuHolder implements InventoryHolder {
    private final AbstractPluginGui menu;
    private final Inventory inventory;
    private Map<Integer, MenuButton> buttons;
    private int page;

    public MenuHolder(AbstractPluginGui menu) {
        this.menu = menu;
        PagedLayout layout = menu.build();
        inventory = Bukkit.createInventory(this, layout.size(), menu.getTitle());
        render(layout);
    }

    public MenuButton getButton(int slot) {
        return buttons.get(slot);
    }

    public void refresh() {
        render(menu.build());
    }

    public void changePage(int delta) {
        page += delta;
        refresh();
    }

    private void render(PagedLayout layout) {
        page = Math.max(0, Math.min(page, layout.pageCount() - 1));
        buttons = layout.getPage(page);
        inventory.clear();
        buttons.forEach((slot, button) -> inventory.setItem(slot, button.createItemStack()));
    }
}
