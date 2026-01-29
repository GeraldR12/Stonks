package me.geraldr12.ui.guis;

import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.*;
import me.geraldr12.ui.items.*;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.gui.structure.Markers;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.SimpleItem;

import java.util.List;
import java.util.stream.Collectors;

public class PortfolioGui extends AbstractPluginGui {

    public PortfolioGui(Player player, GuiManager guiManager, Messages messages) {
        super(messages.getUiPortfolioTitle(), "blockstreet.ui.portfolio", player, guiManager, messages);
    }

    @Override
    public Gui build() {
        List<InvestmentDao> playerInvestments = guiManager.getPlayersService().getInvestments(player.getUniqueId()).stream()
                .filter(investment -> guiManager.getCompaniesService().companyExists(investment.getCompanyId()))
                .collect(Collectors.toList());
        List<Item> investmentsItems = playerInvestments.stream()
                .map(investment -> new InvestmentItem(guiManager.getPlugin(), messages, guiManager.getCompaniesService(), investment))
                .collect(Collectors.toList());

        return PagedGui.items()
                .setStructure(
                        "# # # # o # # # #",
                        "# x x x x x x x #",
                        "# x x x x x x x #",
                        "# x x x x x x x #",
                        "# # # < - > # # #"
                )
                .addIngredient('#', new SimpleItem(new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).setDisplayName("")))
                .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
                .addIngredient('<', new PreviousPageItem(messages))
                .addIngredient('-', new NavigateBackItem(guiManager, messages))
                .addIngredient('>', new NextPageItem(messages))
                .addIngredient('o', new PortfolioSummaryItem(guiManager.getCompaniesService(), playerInvestments, messages))
                .setContent(investmentsItems)
                .build();
    }

}
