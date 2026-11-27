package me.geraldr12.ui.guis;

import java.util.List;
import java.util.Map;
import java.util.Comparator;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.CompanyItem;
import me.geraldr12.ui.items.NotificationSettingsButtonItem;
import me.geraldr12.ui.items.PortfolioButtonItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class CompaniesGui extends AbstractPluginGui {
    private final boolean archive;

    public CompaniesGui(Player player, GuiManager guiManager, Messages messages) {
        this(player, guiManager, messages, false);
    }

    public CompaniesGui(Player player, GuiManager guiManager, Messages messages, boolean archive) {
        super(archive ? messages.getMessageByKey("uiMarketArchive") : messages.getUiCompaniesTitle(),
                "stonks.ui.companies", player, guiManager, messages);
        this.archive = archive;
    }

    @Override
    public PagedLayout build() {
        var companies = guiManager.getCompaniesService().getAllCompanies().stream()
                .filter(archive ? CompanyDao::isBankrupt : CompanyDao::isTrading);
        if (archive) companies = companies.sorted(Comparator.comparing(CompanyDao::getBankruptcyTime,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparingInt(CompanyDao::getId));
        List<MenuButton> items = companies
                .<MenuButton>map(company -> new CompanyItem(guiManager.getPlugin(), messages,
                        guiManager.getCompaniesService(), company.getId())).toList();
        Map<Character, MenuButton> buttons = controls(Material.GRAY_STAINED_GLASS_PANE, "");
        buttons.put('o', new PortfolioButtonItem(guiManager, messages));
        buttons.put('n', new NotificationSettingsButtonItem(guiManager, messages));
        buttons.put('a', new MenuButton(new MenuIcon(archive ? Material.EMERALD : Material.WRITABLE_BOOK,
                messages.getMessageByKey(archive ? "uiMarketActive" : "uiMarketArchive"),
                archive ? List.of() : List.of(messages.getMessageByKey("uiMarketArchiveHint"))),
                (who, click) -> guiManager.openMarketTab(who, !archive)));
        if (items.isEmpty()) items = List.of(new MenuButton(new MenuIcon(Material.PAPER,
                messages.getMessageByKey(archive ? "uiNoBankruptCompanies" : "uiNoActiveCompanies"), List.of()), null));
        return new PagedLayout(new String[]{
                "# # # o # n # a #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# # # < - > # # #"
        }, buttons, items);
    }
}
