package me.geraldr12.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.*;
import lombok.Getter;
import me.geraldr12.Stonks;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.ui.guis.*;
import me.geraldr12.ui.menu.MenuHolder;
import me.geraldr12.ui.menu.MenuListener;
import me.geraldr12.utils.Messages;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

@Singleton
@Getter
public class GuiManager {
    private final Map<UUID, Deque<AbstractPluginGui>> playerStack = new HashMap<>();
    private final Map<UUID, MenuHolder> openMenus = new HashMap<>();
    private final Stonks plugin;
    private final Messages messages;
    private final PlayersService playersService;
    private final CompaniesService companiesService;
    private final MenuListener menuListener;
    private BukkitTask refreshTask;

    @Inject
    public GuiManager(Stonks plugin, Messages messages, PlayersService playersService, CompaniesService companiesService) {
        this.plugin = plugin;
        this.messages = messages;
        this.playersService = playersService;
        this.companiesService = companiesService;
        menuListener = new MenuListener(this, task -> plugin.getServer().getScheduler().runTask(plugin, task));
    }

    public void navigate(Player player, PluginGuiType guiType) {
        if (guiType == PluginGuiType.MAIN_MENU_GUI) {
            navigateMainMenu(player);
        } else {
            navigate(player, createGui(guiType, player));
        }
    }

    public void navigateMainMenu(Player player) {
        AbstractPluginGui main = createGui(PluginGuiType.MAIN_MENU_GUI, player);
        if (!main.canOpen()) return;
        playerStack.remove(player.getUniqueId());
        navigate(player, main);
    }

    private void navigate(Player player, AbstractPluginGui gui) {
        if (!gui.canOpen()) return;
        playerStack.computeIfAbsent(player.getUniqueId(), id -> new LinkedList<>()).push(gui);
        show(player, gui);
    }

    private void show(Player player, AbstractPluginGui gui) {
        openMenus.remove(player.getUniqueId());
        gui.open();
        openMenus.put(player.getUniqueId(), gui.getHolder());
        if (refreshTask == null) {
            refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, 100L, 100L);
        }
    }

    public void navigateBack(Player player) {
        Deque<AbstractPluginGui> stack = playerStack.get(player.getUniqueId());
        if (stack == null || stack.size() <= 1) return;
        AbstractPluginGui previous = stack.stream().skip(1).findFirst().orElseThrow();
        if (!previous.canOpen()) return;
        stack.pop();
        show(player, previous);
    }

    public void close(Player player) {
        MenuHolder holder = openMenus.get(player.getUniqueId());
        player.closeInventory();
        if (holder != null) onClose(player, holder);
    }

    public void onClose(Player player, MenuHolder holder) {
        if (!openMenus.remove(player.getUniqueId(), holder)) return;
        playerStack.remove(player.getUniqueId());
        if (openMenus.isEmpty() && refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        Runnable callback = holder.getMenu().getOnCloseCallback();
        if (callback != null) callback.run();
    }

    private void refreshOpenMenus() {
        for (MenuHolder holder : new ArrayList<>(openMenus.values())) {
            Player player = holder.getMenu().getPlayer();
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) {
                onClose(player, holder);
            } else {
                holder.refresh();
            }
        }
    }

    private AbstractPluginGui createGui(PluginGuiType type, Player player) {
        return switch (type) {
            case MAIN_MENU_GUI -> new MainMenuGui(player, this, messages);
            case PORTFOLIO_GUI -> new PortfolioGui(player, this, messages);
            case COMPANIES_GUI -> new CompaniesGui(player, this, messages);
            case NOTIFICATIONS_GUI -> new NotificationsGui(player, this, messages);
            case PLAYER_COMPANIES_GUI -> new PlayerCompaniesGui(player, this, companiesService, messages);
        };
    }

    public void openCompanyDetailMenu(Player player, long companyId) {
        navigate(player, new CompanyDetailGui(player, this, messages, companyId));
    }

    public void openConfirmDeleteMenu(Player player, long companyId) {
        navigate(player, new ConfirmDeleteGui(player, this, messages, companyId));
    }

    public void openCompanyManagementMenu(Player player, long companyId) {
        navigate(player, new CompanyManagementGui(player, this, messages, companyId));
    }
}
