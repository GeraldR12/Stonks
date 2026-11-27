package me.geraldr12.ui.guis;

import java.util.List;
import java.text.MessageFormat;
import me.geraldr12.commands.ShopLinkCommand;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.DeleteCompanyButtonItem;
import me.geraldr12.ui.items.ShareholderItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class CompanyManagementGui extends AbstractPluginGui {
    private final long companyId;

    public CompanyManagementGui(Player player, GuiManager guiManager, Messages messages, long companyId) {
        super(messages.getMessageByKey("uiManageCompany"), "stonks.use", player, guiManager, messages);
        this.companyId = companyId;
    }

    @Override
    public PagedLayout build() {
        var company = guiManager.getCompaniesService().getCompanyById(companyId);
        boolean canManage = company != null && company.isTrading()
                && player.getUniqueId().toString().equals(company.getOwnerUuid());
        var buttons = controls(Material.GRAY_STAINED_GLASS_PANE, "");
        List<MenuButton> items = !canManage ? List.of(new MenuButton(new MenuIcon(Material.PAPER,
                messages.getMessageByKey("uiMyCompanyUnavailable"), List.of()), null))
                : guiManager.getPlayersService().getShareholdersForCompany(companyId).entrySet().stream()
                .<MenuButton>map(entry -> new ShareholderItem(entry.getKey(), entry.getValue())).toList();
        if (canManage) {
            buttons.put('d', new DeleteCompanyButtonItem(guiManager, messages, companyId));
            var links = guiManager.getPlugin().getShopLinksService();
            if (links != null && player.hasPermission("stonks.command.company.shop")) {
                buttons.put('s', new MenuButton(new MenuIcon(Material.CHEST,
                        messages.getMessageByKey("uiCompanyShopList"), List.of(MessageFormat.format(
                        messages.getMessageByKey("uiCompanyShopsCount"), links.linksOf(companyId).size()))),
                        (who, click) -> shop(who, "list")));
                buttons.put('l', new MenuButton(new MenuIcon(Material.TRIPWIRE_HOOK,
                        messages.getMessageByKey("uiCompanyShopLink"), List.of(messages.getMessageByKey("uiCompanyShopTarget"))),
                        (who, click) -> shop(who, "link")));
                buttons.put('u', new MenuButton(new MenuIcon(Material.SHEARS,
                        messages.getMessageByKey("uiCompanyShopUnlink"), List.of(messages.getMessageByKey("uiCompanyShopTarget"))),
                        (who, click) -> shop(who, "unlink")));
            }
        }
        return new PagedLayout(new String[]{
                "x x x x x x x x x",
                "x x x x x x x x x",
                "l s u < - > . . d"
        }, buttons, items);
    }

    private void shop(Player who, String action) {
        if (canOpen()) new ShopLinkCommand(guiManager.getPlugin()).onCommand(who,
                new String[]{action, Long.toString(companyId)});
    }
}
