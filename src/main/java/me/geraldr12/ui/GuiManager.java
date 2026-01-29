package me.geraldr12.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.geraldr12.Stonks;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.ui.guis.CompaniesGui;
import me.geraldr12.ui.guis.NotificationsGui;
import me.geraldr12.ui.guis.PlayerCompaniesGui;
import me.geraldr12.ui.guis.PortfolioGui;
import me.geraldr12.ui.items.DeleteCompanyButtonItem;
import me.geraldr12.ui.items.ShareholderItem;
import me.geraldr12.utils.Messages;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Getter;
import org.bukkit.entity.Player;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.structure.Markers;
import xyz.xenondevs.invui.window.Window;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.gui.structure.Structure;
import xyz.xenondevs.invui.gui.PagedGui;

@Singleton
@Getter
public class GuiManager {

    private final Map<UUID, Deque<AbstractPluginGui>> playerStack = new HashMap<>();
    private final Stonks plugin;
    private final Messages messages;
    private final PlayersService playersService;
    private final CompaniesService companiesService;

    @Inject
    public GuiManager(Stonks plugin, Messages messages, PlayersService playersService, CompaniesService companiesService) {
        this.plugin = plugin;
        this.messages = messages;
        this.playersService = playersService;
        this.companiesService = companiesService;
    }

    public void navigate(Player player, PluginGuiType guiType) {
        AbstractPluginGui gui = createGui(guiType, player);
        if (!playerStack.containsKey(player.getUniqueId())) {
            playerStack.put(player.getUniqueId(), new LinkedList<>());
        }
        playerStack.get(player.getUniqueId()).push(gui);
        gui.open();
    }

    public void navigateBack(Player player) {
        if (!playerStack.containsKey(player.getUniqueId())) {
            return;
        }
        Deque<AbstractPluginGui> stack = playerStack.get(player.getUniqueId());
        if (stack.size() <= 1) {
            return;
        }
        stack.pop().close();
        stack.peek().open();
    }

    public void close(Player player) {
        player.closeInventory();
        if (!playerStack.containsKey(player.getUniqueId())) {
            return;
        }
        Deque<AbstractPluginGui> stack = playerStack.get(player.getUniqueId());
        if (!stack.isEmpty()) {
            stack.pop().close();
            stack.clear();
        }
    }

    private AbstractPluginGui createGui(PluginGuiType pluginGuiType, Player player) {
        switch (pluginGuiType) {
            case PORTFOLIO_GUI:
                return new PortfolioGui(player, this, messages);
            case COMPANIES_GUI:
                return new CompaniesGui(player, this, messages);
            case NOTIFICATIONS_GUI:
                return new NotificationsGui(player, this, messages);
            case PLAYER_COMPANIES_GUI:
                return new PlayerCompaniesGui(player, this, companiesService, messages);
            default:
                throw new IllegalArgumentException("Unknown GUI type: " + pluginGuiType);
        }
    }

    public void openCompanyManagementMenu(Player player, long companyId) {
        // 1. Get shareholders
        Map<UUID, Integer> shareholders = playersService.getShareholdersForCompany(companyId);
        List<Item> items = shareholders.entrySet().stream()
                .map(entry -> new ShareholderItem(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());

        // 2. Define the structure
        Structure structure = new Structure(
                "x x x x x x x x x",
                "x x x x x x x x x",
                ". . . . . . . . d")
                .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
                .addIngredient('d', new DeleteCompanyButtonItem(plugin, companyId));

        // 3. Use PagedGui.items() - This is the standard for lists with content
        Gui gui = PagedGui.items()
                .setStructure(structure)
                .setContent(items)
                .build();

        Window window = Window.single()
                .setViewer(player)
                .setTitle("Managing Company ID: " + companyId)
                .setGui(gui)
                .build();

        window.open();
    }
}