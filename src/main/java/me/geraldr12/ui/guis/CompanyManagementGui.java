package me.geraldr12.ui.guis;

import java.util.List;
import java.util.Map;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.DeleteCompanyButtonItem;
import me.geraldr12.ui.items.ShareholderItem;
import me.geraldr12.ui.items.NavigateBackItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.Messages;
import org.bukkit.entity.Player;

public class CompanyManagementGui extends AbstractPluginGui {
    private final long companyId;

    public CompanyManagementGui(Player player, GuiManager guiManager, Messages messages, long companyId) {
        super("Managing Company ID: " + companyId, null, player, guiManager, messages);
        this.companyId = companyId;
    }

    @Override
    public PagedLayout build() {
        List<MenuButton> items = guiManager.getPlayersService().getShareholdersForCompany(companyId).entrySet().stream()
                .<MenuButton>map(entry -> new ShareholderItem(entry.getKey(), entry.getValue())).toList();
        return new PagedLayout(new String[]{
                "x x x x x x x x x",
                "x x x x x x x x x",
                ". . . . - . . . d"
        }, Map.of('d', new DeleteCompanyButtonItem(guiManager, messages, companyId),
                '-', new NavigateBackItem(guiManager, messages, true)), items);
    }
}
