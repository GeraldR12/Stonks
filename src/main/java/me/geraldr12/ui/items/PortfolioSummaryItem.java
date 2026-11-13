package me.geraldr12.ui.items;

import java.text.MessageFormat;
import java.util.List;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.VisualizationUtils;
import org.bukkit.Material;

public class PortfolioSummaryItem extends MenuButton {
    public PortfolioSummaryItem(CompaniesService companiesService, List<InvestmentDao> investments, Messages messages) {
        super(icon(companiesService, investments, messages), null);
    }

    private static MenuIcon icon(CompaniesService companiesService, List<InvestmentDao> investments, Messages messages) {
        int totalShares = investments.stream().mapToInt(InvestmentDao::getSharesAmount).sum();
        double totalValue = investments.stream()
                .filter(investment -> companiesService.companyExists(investment.getCompanyId()))
                .mapToDouble(investment -> investment.getSharesAmount()
                        * companiesService.getCompanyById(investment.getCompanyId()).getCurrentSharePrice()).sum();
        double totalInvestment = investments.stream()
                .mapToDouble(investment -> investment.getSharesAmount() * investment.getAverageBuyPrice()).sum();
        double profitPercentage = (totalValue - totalInvestment) / totalInvestment * 100;
        return new MenuIcon(Material.DARK_OAK_SIGN,
                messages.getUiPortfolioSummaryItemTitle() + MessageFormat.format(messages.getUiCompanyItemLastVariation(),
                        VisualizationUtils.formatCompanyVariation(profitPercentage)),
                List.of("", MessageFormat.format(messages.getUiPortfolioSummaryItemCompanies(), investments.size()),
                        MessageFormat.format(messages.getUiPortfolioSummaryItemShares(), totalShares),
                        MessageFormat.format(messages.getUiPortfolioSummaryItemTotalValue(), FormattingUtils.formatDouble(totalValue))));
    }
}
