package me.geraldr12.ui.guis;

import java.util.List;
import java.util.Map;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.InvestmentItem;
import me.geraldr12.ui.items.PortfolioSummaryItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class PortfolioGui extends AbstractPluginGui {
    public PortfolioGui(Player player, GuiManager guiManager, Messages messages) {
        super(messages.getUiPortfolioTitle(), "stonks.ui.portfolio", player, guiManager, messages);
    }

    @Override
    public PagedLayout build() {
        List<InvestmentDao> investments = guiManager.getPlayersService().getInvestments(player.getUniqueId()).stream()
                .filter(investment -> guiManager.getCompaniesService().companyExists(investment.getCompanyId())).toList();
        List<MenuButton> items = investments.stream()
                .<MenuButton>map(investment -> new InvestmentItem(guiManager.getPlugin(), messages,
                        guiManager.getCompaniesService(), investment)).toList();
        Map<Character, MenuButton> buttons = controls(Material.GRAY_STAINED_GLASS_PANE, "");
        buttons.put('o', new PortfolioSummaryItem(guiManager.getCompaniesService(), investments, messages));
        return new PagedLayout(new String[]{
                "# # # # o # # # #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# # # < - > # # #"
        }, buttons, items);
    }
}
