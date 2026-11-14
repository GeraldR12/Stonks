package me.geraldr12.ui.guis;

import java.util.List;
import java.util.Map;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.CompanyItem;
import me.geraldr12.ui.items.NotificationSettingsButtonItem;
import me.geraldr12.ui.items.PortfolioButtonItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class CompaniesGui extends AbstractPluginGui {
    public CompaniesGui(Player player, GuiManager guiManager, Messages messages) {
        super(messages.getUiCompaniesTitle(), "stonks.ui.companies", player, guiManager, messages);
    }

    @Override
    public PagedLayout build() {
        List<MenuButton> items = guiManager.getCompaniesService().getAllCompanies().stream()
                .<MenuButton>map(company -> new CompanyItem(guiManager.getPlugin(), messages,
                        guiManager.getCompaniesService(), company.getId())).toList();
        Map<Character, MenuButton> buttons = controls(Material.GRAY_STAINED_GLASS_PANE, "");
        buttons.put('o', new PortfolioButtonItem(guiManager, messages));
        buttons.put('n', new NotificationSettingsButtonItem(guiManager, messages));
        return new PagedLayout(new String[]{
                "# # # o # n # # #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# # # < - > # # #"
        }, buttons, items);
    }
}
