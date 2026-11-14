package me.geraldr12.ui.guis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.PluginGuiType;
import me.geraldr12.ui.items.PortfolioSummaryItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class MainMenuGui extends AbstractPluginGui {
    public MainMenuGui(Player player, GuiManager guiManager, Messages messages) {
        super(messages.getMessageByKey("uiMainMenuTitle"), "stonks.use", player, guiManager, messages);
    }

    @Override
    public PagedLayout build() {
        var investments = guiManager.getPlayersService().getInvestments(player.getUniqueId()).stream()
                .filter(investment -> guiManager.getCompaniesService().companyExists(investment.getCompanyId())).toList();
        var summary = new PortfolioSummaryItem(guiManager.getCompaniesService(), investments, messages);
        Map<Character, MenuButton> buttons = new HashMap<>();
        buttons.put('m', navigation(Material.EMERALD, "uiMainMarket", PluginGuiType.COMPANIES_GUI));
        buttons.put('p', new MenuButton(new MenuIcon(Material.DARK_OAK_SIGN,
                messages.getMessageByKey("uiMainPortfolio"), summary.getIcon().lore()),
                (who, click) -> guiManager.navigate(who, PluginGuiType.PORTFOLIO_GUI)));
        buttons.put('c', navigation(Material.CHEST, "uiMainCompanies", PluginGuiType.PLAYER_COMPANIES_GUI));
        buttons.put('n', navigation(Material.BELL, "uiMainNotifications", PluginGuiType.NOTIFICATIONS_GUI));
        buttons.put('h', new MenuButton(new MenuIcon(Material.BOOK, messages.getMessageByKey("uiMainHelp"),
                List.of(messages.getMessageByKey("uiMainHelpBuy"), messages.getMessageByKey("uiMainHelpSell"),
                        messages.getMessageByKey("uiMainHelpPortfolio"), messages.getMessageByKey("uiMainHelpCompanies"),
                        messages.getMessageByKey("uiMainHelpCreate"), messages.getMessageByKey("uiMainHelpDelete"))), null));
        buttons.put('q', new MenuButton(new MenuIcon(Material.BARRIER, messages.getMessageByKey("uiMainClose"), List.of()),
                (who, click) -> guiManager.close(who)));
        return new PagedLayout(new String[]{
                ". . . . . . . . .",
                ". m . p . c . n .",
                ". . . . h . . . q"
        }, buttons, List.of());
    }

    private MenuButton navigation(Material material, String key, PluginGuiType destination) {
        return new MenuButton(new MenuIcon(material, messages.getMessageByKey(key), List.of()),
                (who, click) -> guiManager.navigate(who, destination));
    }
}
