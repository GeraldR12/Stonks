package me.geraldr12.ui.guis;

import java.util.List;
import java.util.Map;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.PluginGuiType;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class GuideGui extends AbstractPluginGui {
    public GuideGui(Player player, GuiManager manager, Messages messages) {
        super(messages.getMessageByKey("uiGuideTitle"), "stonks.use", player, manager, messages);
    }

    @Override
    public PagedLayout build() {
        Map<Character, MenuButton> buttons = controls(Material.GRAY_STAINED_GLASS_PANE, "");
        buttons.put('t', topic(Material.EMERALD, "uiGuideTrade", PluginGuiType.COMPANIES_GUI));
        buttons.put('p', topic(Material.CHEST, "uiGuidePortfolio", PluginGuiType.PORTFOLIO_GUI));
        buttons.put('c', topic(Material.ANVIL, "uiGuideCompany", PluginGuiType.PLAYER_COMPANIES_GUI));
        buttons.put('b', topic(Material.WRITABLE_BOOK, "uiGuideBankruptcy", PluginGuiType.BANKRUPT_COMPANIES_GUI));
        buttons.put('n', topic(Material.BELL, "uiGuideNotifications", PluginGuiType.NOTIFICATIONS_GUI));
        buttons.put('s', new MenuButton(new MenuIcon(Material.PAPER, messages.getMessageByKey("uiGuideCommands"),
                List.of(messages.getMessageByKey("uiGuideCommandsHint"), messages.getMessageByKey("uiMainHelpBuy"),
                        messages.getMessageByKey("uiMainHelpSell"), messages.getMessageByKey("uiMainHelpCreate"),
                        messages.getMessageByKey("uiMainHelpDelete"))), null));
        return new PagedLayout(new String[]{
                "# # # # # # # # #",
                "# t # p # c # b #",
                "# # n # - # s # #"
        }, buttons, List.of());
    }

    private MenuButton topic(Material material, String key, PluginGuiType destination) {
        return new MenuButton(new MenuIcon(material, messages.getMessageByKey(key),
                List.of(messages.getMessageByKey(key + "Hint"))),
                (who, click) -> guiManager.navigate(who, destination));
    }
}
