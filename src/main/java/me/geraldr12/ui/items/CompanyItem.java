package me.geraldr12.ui.items;

import java.text.MessageFormat;
import java.util.List;
import me.geraldr12.Stonks;
import me.geraldr12.commands.BuyCommand;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.VisualizationUtils;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

public class CompanyItem extends MenuButton {
    public CompanyItem(Stonks plugin, Messages messages, CompaniesService companiesService, int companyId) {
        super(icon(companiesService.getCompanyById(companyId), messages), (player, click) -> {
            CompanyDao company = companiesService.getCompanyById(companyId);
            if (company == null) return;
            if (click == ClickType.LEFT) {
                if (player.getUniqueId().toString().equals(company.getOwnerUuid())) {
                    plugin.getGuiManager().openCompanyManagementMenu(player, companyId);
                } else {
                    plugin.getGuiManager().openCompanyDetailMenu(player, companyId);
                }
                return;
            }
            int quantity = sharesForClick(click);
            if (quantity > 0) {
                new BuyCommand(plugin).onCommand(player, new String[]{String.valueOf(companyId), String.valueOf(quantity)});
            }
        });
    }

    public static int sharesForClick(ClickType click) {
        return switch (click) {
            case RIGHT -> 1;
            case SHIFT_RIGHT -> 10;
            case SHIFT_LEFT -> 100;
            default -> 0;
        };
    }

    private static MenuIcon icon(CompanyDao company, Messages messages) {
        if (company == null) return new MenuIcon(Material.AIR, "", List.of());
        double marketCap = company.getCurrentSharePrice() * company.getTotalShares();
        double totalVariation = (company.getCurrentSharePrice() - company.getInitialSharePrice()) / company.getInitialSharePrice() * 100;
        double lastVariation = !company.getHistoric().isEmpty() ? company.getHistoric().peek().getVariation() * 100 : 0;
        return new MenuIcon(company.getIcon() != null ? company.getIcon() : Material.EMERALD,
                ChatColor.GOLD + company.getName() + (!company.isBankrupt() ? MessageFormat.format(
                        messages.getUiCompanyItemLastVariation(), VisualizationUtils.formatCompanyVariation(lastVariation)) : ""),
                List.of(company.isBankrupt() ? messages.getCompanyStatusBankrupt() : messages.getCompanyStatusTrading(),
                        "",
                        MessageFormat.format(messages.getUiCompanyItemRisk(), company.getRisk()),
                        MessageFormat.format(messages.getUiCompanyItemSharePrice(), FormattingUtils.formatDouble(company.getCurrentSharePrice())),
                        MessageFormat.format(messages.getUiCompanyItemMarketCap(), FormattingUtils.formatDouble(marketCap)),
                        MessageFormat.format(messages.getUiCompanyItemAllTimeVariation(), VisualizationUtils.formatCompanyVariation(totalVariation)),
                        "", messages.getUiCompanyItemBuyOneShare(), messages.getUiCompanyItemBuyTenShares(),
                        messages.getUiCompanyItemBuyHundredShares()));
    }

    @Override
    public ItemStack createItemStack() {
        ItemStack item = super.createItemStack();
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_POTION_EFFECTS);
            item.setItemMeta(meta);
        }
        return item;
    }
}
