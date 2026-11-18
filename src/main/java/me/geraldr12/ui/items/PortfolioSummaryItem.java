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
        long totalShares = investments.stream().filter(investment -> {
            var company = companiesService.getCompanyById(investment.getCompanyId());
            return company != null && !company.isBankrupt();
        }).mapToLong(InvestmentDao::getSharesAmount).sum();
        double totalValue = investments.stream()
                .mapToDouble(investment -> {
                    var company = companiesService.getCompanyById(investment.getCompanyId());
                    return company != null && company.isTrading()
                            ? investment.getSharesAmount() * company.getCurrentSharePrice() : 0.0;
                }).sum();
        double totalInvestment = investments.stream()
                .mapToDouble(investment -> investment.getSharesAmount() * investment.getAverageBuyPrice()).sum();
        double profitPercentage = totalInvestment > 0 ? (totalValue - totalInvestment) / totalInvestment * 100 : 0;
        return new MenuIcon(Material.DARK_OAK_SIGN,
                messages.getUiPortfolioSummaryItemTitle() + MessageFormat.format(messages.getUiCompanyItemLastVariation(),
                        VisualizationUtils.formatCompanyVariation(profitPercentage)),
                List.of("", MessageFormat.format(messages.getUiPortfolioSummaryItemCompanies(), investments.size()),
                        MessageFormat.format(messages.getUiPortfolioSummaryItemShares(), totalShares),
                        MessageFormat.format(messages.getUiPortfolioSummaryItemTotalValue(), FormattingUtils.formatDouble(totalValue))));
    }
}
