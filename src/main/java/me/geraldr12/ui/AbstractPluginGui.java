package me.geraldr12.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import me.geraldr12.ui.items.NavigateBackItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuHolder;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

@Getter
@RequiredArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public abstract class AbstractPluginGui {
    protected final String title;
    protected final String permission;
    protected final Player player;
    protected final GuiManager guiManager;
    protected final Messages messages;
    @Setter protected Runnable onCloseCallback;
    protected MenuHolder holder;

    public boolean canOpen() {
        if (permission == null || player.hasPermission(permission)) return true;
        player.sendMessage(messages.getNoPermission());
        return false;
    }

    public void open() {
        if (!canOpen()) return;
        if (holder == null) holder = new MenuHolder(this);
        else holder.refresh();
        player.openInventory(holder.getInventory());
    }

    public void close() {
        if (holder != null && player.getOpenInventory().getTopInventory().getHolder() == holder) {
            player.closeInventory();
        }
    }

    protected Map<Character, MenuButton> controls(Material border, String borderName) {
        Map<Character, MenuButton> buttons = new HashMap<>();
        buttons.put('#', new MenuButton(new MenuIcon(border, borderName, List.of()), null));
        buttons.put('<', new MenuButton(new MenuIcon(Material.RED_STAINED_GLASS_PANE,
                messages.getUiPreviousPage(), List.of()), (who, click) -> holder.changePage(-1)));
        buttons.put('>', new MenuButton(new MenuIcon(Material.GREEN_STAINED_GLASS_PANE,
                messages.getUiNextPage(), List.of()), (who, click) -> holder.changePage(1)));
        buttons.put('-', new NavigateBackItem(guiManager, messages));
        return buttons;
    }

    public abstract PagedLayout build();
}
