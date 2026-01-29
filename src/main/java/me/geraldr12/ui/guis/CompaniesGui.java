package me.geraldr12.ui.guis;

import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.CompanyItem;
import me.geraldr12.ui.items.NextPageItem;
import me.geraldr12.ui.items.NotificationSettingsButtonItem;
import me.geraldr12.ui.items.PortfolioButtonItem;
import me.geraldr12.ui.items.PreviousPageItem;
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

public class CompaniesGui extends AbstractPluginGui {

    public CompaniesGui(Player player, GuiManager guiManager, Messages messages) {
        super(messages.getUiCompaniesTitle(), "blockstreet.ui.companies", player, guiManager, messages);
    }

    @Override
    public Gui build() {
        List<Item> companiesItems = guiManager.getCompaniesService().getAllCompanies()
                .stream()
                .map(company -> new CompanyItem(guiManager.getPlugin(), messages, guiManager.getCompaniesService(), company.getId()))
                .collect(Collectors.toList());

        return PagedGui.items()
                .setStructure(
                        "# # # o # n # # #",
                        "# x x x x x x x #",
                        "# x x x x x x x #",
                        "# x x x x x x x #",
                        "# # # < # > # # #"
                )
                .addIngredient('#', new SimpleItem(new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).setDisplayName("")))
                .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
                .addIngredient('<', new PreviousPageItem(messages))
                .addIngredient('>', new NextPageItem(messages))
                .addIngredient('o', new PortfolioButtonItem(guiManager, messages))
                .addIngredient('n', new NotificationSettingsButtonItem(guiManager, messages))
                .setContent(companiesItems)
                .build();
    }

}
