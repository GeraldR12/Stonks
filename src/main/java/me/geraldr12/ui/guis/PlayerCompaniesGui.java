package me.geraldr12.ui.guis;

import java.util.List;
import java.util.Map;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.CompanyItem;
import me.geraldr12.ui.items.CreateCompanyButtonItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class PlayerCompaniesGui extends AbstractPluginGui {
    private final CompaniesService companiesService;

    public PlayerCompaniesGui(Player player, GuiManager guiManager, CompaniesService companiesService, Messages messages) {
        super("§8My Companies", "stonks.command.company", player, guiManager, messages);
        this.companiesService = companiesService;
    }

    @Override
    public PagedLayout build() {
        List<MenuButton> items = companiesService.getAllCompanies().stream()
                .filter(company -> player.getUniqueId().toString().equals(company.getOwnerUuid()))
                .<MenuButton>map(company -> new CompanyItem(guiManager.getPlugin(), messages, companiesService, company.getId())).toList();
        Map<Character, MenuButton> buttons = controls(Material.BLACK_STAINED_GLASS_PANE, " ");
        buttons.put('C', new CreateCompanyButtonItem(guiManager, messages));
        return new PagedLayout(new String[]{
                "# # # # # # # # #",
                "# x x x x x x x #",
                "# # # # C # # # -"
        }, buttons, items);
    }
}
