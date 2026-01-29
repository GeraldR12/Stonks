package me.geraldr12.ui.guis;

import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.CompanyItem;
import me.geraldr12.ui.items.CreateCompanyButtonItem;
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

public class PlayerCompaniesGui extends AbstractPluginGui {

    private final CompaniesService companiesService;

    public PlayerCompaniesGui(Player player, GuiManager guiManager, CompaniesService companiesService, Messages messages) {
        // Change the first argument to "My Companies"
        super("§8My Companies", "stonks.command.company", player, guiManager, messages);
        this.companiesService = companiesService;
    }

    @Override
    public Gui getGui() {
        return build();
    }

    @Override
    public Gui build() {
        List<Item> myCompanies = companiesService.getAllCompanies().stream()
                .filter(c -> player.getUniqueId().toString().equals(c.getOwnerUuid()))
                .map(company -> new CompanyItem(guiManager.getPlugin(), messages, companiesService, company.getId()))
                .collect(Collectors.toList());

        return PagedGui.items()
                .setStructure(
                        "# # # # # # # # #",
                        "# x x x x x x x #",
                        "# # # # C # # # #"
                )
                // If you forget this line, the '#' won't show up and the GUI might look like the market one
                .addIngredient('#', new SimpleItem(new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).setDisplayName(" ")))
                .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
                .addIngredient('C', new CreateCompanyButtonItem(guiManager, messages))
                .setContent(myCompanies)
                .build();
    }
}