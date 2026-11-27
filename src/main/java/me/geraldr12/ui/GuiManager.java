package me.geraldr12.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.*;
import java.util.function.Consumer;
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
        menuListener.clearInput(player);
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

    public void onQuit(Player player) {
        MenuHolder holder = openMenus.get(player.getUniqueId());
        if (holder != null) onClose(player, holder);
        else playerStack.remove(player.getUniqueId());
    }

    public void openMarketTab(Player player, boolean archive) {
        var tab = new CompaniesGui(player, this, messages, archive);
        if (!tab.canOpen()) return;
        var stack = playerStack.get(player.getUniqueId());
        if (stack != null && stack.peek() instanceof CompaniesGui) stack.pop();
        navigate(player, tab);
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
            case BANKRUPT_COMPANIES_GUI -> new CompaniesGui(player, this, messages, true);
            case GUIDE_GUI -> new GuideGui(player, this, messages);
            case NOTIFICATIONS_GUI -> new NotificationsGui(player, this, messages);
            case PLAYER_COMPANIES_GUI -> new PlayerCompaniesGui(player, this, companiesService, messages);
        };
    }

    public void openCompanyDetailMenu(Player player, long companyId) {
        navigate(player, new CompanyDetailGui(player, this, messages, companyId));
    }

    public void openTradeQuote(Player player, long companyId, int quantity, boolean buy) {
        if (!player.hasPermission("stonks.use")) {
            player.sendMessage(messages.getMessageByKey("uiTradePermission"));
            return;
        }
        navigate(player, new TradeQuoteGui(player, this, messages, companyId, quantity, buy));
    }

    public void openCompanyHistory(Player player, long companyId) {
        navigate(player, new CompanyHistoryGui(player, this, messages, companyId));
    }

    public void openCompanyCreation(Player player) {
        navigate(player, new CreateCompanyGui(player, this, messages));
    }

    public void prompt(AbstractPluginGui menu, String message, Consumer<String> response) {
        Player player = menu.getPlayer();
        UUID id = player.getUniqueId();
        if (openMenus.get(id) != menu.getHolder()) return;
        openMenus.remove(id);
        if (openMenus.isEmpty() && refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        player.closeInventory();
        menuListener.expectInput(player, input -> {
            Deque<AbstractPluginGui> stack = playerStack.get(id);
            if (stack == null || stack.peek() != menu || !menu.canOpen()) return;
            show(player, menu);
            if (!input.equalsIgnoreCase("cancel")) response.accept(input);
        });
        player.sendMessage(messages.getMessageByKey(message));
    }

    public void openConfirmDeleteMenu(Player player, long companyId) {
        navigate(player, new ConfirmDeleteGui(player, this, messages, companyId));
    }

    public void openCompanyManagementMenu(Player player, long companyId) {
        navigate(player, new CompanyManagementGui(player, this, messages, companyId));
    }
}
