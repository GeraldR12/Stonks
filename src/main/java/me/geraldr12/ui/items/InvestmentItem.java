package me.geraldr12.ui.items;

import java.text.MessageFormat;
import java.util.List;
import me.geraldr12.Stonks;
import me.geraldr12.commands.SellCommand;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
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

public class InvestmentItem extends MenuButton {
    public InvestmentItem(Stonks plugin, Messages messages, CompaniesService companiesService, InvestmentDao investment) {
        super(icon(messages, companiesService, investment), (player, click) -> {
            int quantity = 0;
            if (click == ClickType.RIGHT) quantity = 1;
            else if (click == ClickType.SHIFT_RIGHT) {
                quantity = plugin.getPlayersService().getInvestments(player.getUniqueId()).stream()
                        .filter(current -> current.getCompanyId() == investment.getCompanyId())
                        .mapToInt(InvestmentDao::getSharesAmount).findFirst().orElse(0);
            }
            if (quantity > 0) {
                new SellCommand(plugin).onCommand(player,
                        new String[]{String.valueOf(investment.getCompanyId()), String.valueOf(quantity)});
            }
        });
    }

    private static MenuIcon icon(Messages messages, CompaniesService companiesService, InvestmentDao investment) {
        CompanyDao company = companiesService.getCompanyById(investment.getCompanyId());
        if (company == null) return new MenuIcon(Material.AIR, "", List.of());
        double currentValue = investment.getSharesAmount() * company.getCurrentSharePrice();
        return new MenuIcon(company.getIcon() != null ? company.getIcon() : Material.DIAMOND,
                ChatColor.GOLD + company.getName() + (!company.isBankrupt() ? MessageFormat.format(
                        messages.getUiCompanyItemLastVariation(), VisualizationUtils.formatCompanyVariation(
                                investment.getInvestmentVariation(company.getCurrentSharePrice()))) : ""),
                List.of(company.isBankrupt() ? messages.getCompanyStatusBankrupt() : messages.getCompanyStatusTrading(),
                        "",
                        MessageFormat.format(messages.getUiInvestmentItemShares(), investment.getSharesAmount()),
                        MessageFormat.format(messages.getUiInvestmentItemCurrentValue(), FormattingUtils.formatDouble(currentValue)),
                        MessageFormat.format(messages.getUiInvestmentItemAverageBuyPrice(), FormattingUtils.formatDouble(investment.getAverageBuyPrice())),
                        "", messages.getUiInvestmentItemSellOne(), messages.getUiInvestmentItemSellAll()));
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
