package me.geraldr12.ui.items;

import me.geraldr12.Stonks;
import me.geraldr12.commands.SellCommand;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.VisualizationUtils;
// REMOVE dev-command imports
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AutoUpdateItem;

import java.text.MessageFormat;
import java.util.List;

public class InvestmentItem extends AutoUpdateItem {

    private final Stonks plugin;
    private final InvestmentDao investment;

    public InvestmentItem(Stonks plugin, Messages messages, CompaniesService companiesService, InvestmentDao investment) {
        super(20 * 5, () -> getItemProvider(messages, companiesService, investment));
        this.plugin = plugin;
        this.investment = investment;
    }

    public static ItemProvider getItemProvider(Messages messages, CompaniesService companiesService, InvestmentDao investment) {
        if (!companiesService.companyExists(investment.getCompanyId())) {
            return new ItemBuilder(Material.AIR);
        }

        CompanyDao company = companiesService.getCompanyById(investment.getCompanyId());
        double currentValue = investment.getSharesAmount() * company.getCurrentSharePrice();

        return new ItemBuilder(company.getIcon() != null ? company.getIcon() : Material.DIAMOND)
                .setDisplayName(ChatColor.GOLD + company.getName() + (!company.isBankrupt() ? MessageFormat.format(messages.getUiCompanyItemLastVariation(),
                        VisualizationUtils.formatCompanyVariation(investment.getInvestmentVariation(company.getCurrentSharePrice()))) : ""))
                .setItemFlags(List.of(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_POTION_EFFECTS))
                .addLoreLines(
                        company.isBankrupt() ? messages.getCompanyStatusBankrupt() : messages.getCompanyStatusTrading(),
                        "",
                        MessageFormat.format(messages.getUiInvestmentItemShares(), investment.getSharesAmount()),
                        MessageFormat.format(messages.getUiInvestmentItemCurrentValue(), FormattingUtils.formatDouble(currentValue)),
                        MessageFormat.format(messages.getUiInvestmentItemAverageBuyPrice(), FormattingUtils.formatDouble(investment.getAverageBuyPrice())),
                        "",
                        messages.getUiInvestmentItemSellOne(),
                        messages.getUiInvestmentItemSellAll()
                );
    }

    @Override
    public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent inventoryClickEvent) {
        int sharesToSell = -1;
        if (clickType == ClickType.RIGHT) {
            sharesToSell = 1;
        } else if (clickType == ClickType.SHIFT_RIGHT) {
            sharesToSell = investment.getSharesAmount(); // Sell all shares
        }

        if (sharesToSell <= 0) {
            return;
        }

        // DIRECT EXECUTION: Call the converted SellCommand directly
        SellCommand sellLogic = new SellCommand(plugin);
        sellLogic.onCommand(player, new String[]{String.valueOf(investment.getCompanyId()), String.valueOf(sharesToSell)});

        // Local state update for the GUI
        if (investment.getSharesAmount() - sharesToSell >= 0) {
            investment.setSharesAmount(investment.getSharesAmount() - sharesToSell);
        }
        notifyWindows();
    }

}