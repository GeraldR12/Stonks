package me.geraldr12.schedulers;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.QuoteDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.enums.NotificationType;
import me.geraldr12.events.CompanyBankruptEvent;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.random.StocksRandomizer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import javax.inject.Inject;
import java.text.MessageFormat;
import java.util.List;

public class InterestRateScheduler extends BukkitRunnable {

    private final Stonks plugin;
    private final CompaniesService companiesService;
    private final SignsService signsService;
    private final PlayersService playersService;
    private final Messages messages;

    @Inject
    public InterestRateScheduler(Stonks plugin, CompaniesService companiesService, SignsService signsService,
                                 PlayersService playersService, Messages messages) {
        this.plugin = plugin;
        this.companiesService = companiesService;
        this.signsService = signsService;
        this.playersService = playersService;
        this.messages = messages;
    }

    @Override
    public void run() {
        List<CompanyDao> allCompanies = companiesService.getAllCompanies();

        for (CompanyDao company : allCompanies) {
            // 1. Skip if already processed or fully removed
            if (company.isBankrupt()) {
                // Still update signs for bankrupt companies to ensure they stay "BANKRUPT"
                updateSigns(company.getId());
                continue;
            }

            // 2. Base the tick on CURRENT price (Player Impact Included)
            double currentPrice = company.getCurrentSharePrice();
            StocksRandomizer stocksRandomizer = new StocksRandomizer(company.getRisk(), company.getInitialSharePrice());

            double newSharesQuote = stocksRandomizer.getRandomQuote(currentPrice);
            double newSharePrice = stocksRandomizer.getRandomStockValue(currentPrice, newSharesQuote);

            // 3. Crash Logic
            if (shouldCrash(company.getRisk(), stocksRandomizer, newSharePrice)) {
                newSharePrice = 0;
            }

            // 4. Update the Value
            companiesService.updateCompanySharesValue(company.getId(), newSharePrice, newSharesQuote);

            // 5. Update History
            QuoteDao quoteDao = new QuoteDao(newSharesQuote, newSharePrice, System.currentTimeMillis());
            companiesService.updateCompanyHistoric(company.getId(), quoteDao);

            // 6. Handle New Bankruptcy
            if (newSharePrice <= 0) {
                handleBankruptcy(company);
            }

            // 7. Update Signs (Safe method)
            updateSigns(company.getId());
        }

        broadcastUpdate();
    }

    private void handleBankruptcy(CompanyDao company) {
        CompanyBankruptEvent bankruptEvent = new CompanyBankruptEvent(company);
        Bukkit.getPluginManager().callEvent(bankruptEvent);

        processBankruptCompany(company);

        plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> playersService.hasNotificationEnabled(player.getUniqueId(), NotificationType.COMPANY_BANKRUPT))
                .forEach(player -> player.sendMessage(messages.getPluginPrefix() +
                        MessageFormat.format(messages.getCompanyStocksCrashed(), company.getName())));
    }

    private void updateSigns(long companyId) {
        // Run on main thread to avoid Bukkit API async errors
        Bukkit.getScheduler().runTask(plugin, () -> {
            signsService.updateBukkitSignsByCompany(companyId);
        });
    }

    private void broadcastUpdate() {
        plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> playersService.hasNotificationEnabled(player.getUniqueId(), NotificationType.STOCKS_UPDATE))
                .forEach(player -> player.sendMessage(messages.getPluginPrefix() + messages.getUpdatedInterestRate()));

        plugin.getLogger().info("The values of all stocks have been updated!");
    }

    private boolean shouldCrash(int companyRisk, StocksRandomizer stocksRandomizer, double newSharePrice) {
        boolean isStockCrashEnabled = plugin.getConfig().getBoolean("Stonks.StockCrash.Enabled", true);
        double dangerZonePercentage = plugin.getConfig().getDouble("Stonks.StockCrash.Aggressiveness", 0.7);

        if (!isStockCrashEnabled || dangerZonePercentage <= 0) return false;

        boolean chance = Math.random() < 0.3 * (Math.pow(1 + 0.35 * dangerZonePercentage, companyRisk)) - 0.35;
        return chance && stocksRandomizer.canCrash(newSharePrice);
    }

    private void processBankruptCompany(CompanyDao company) {
        boolean shouldRemove = plugin.getConfig().getBoolean("Stonks.StockCrash.RemoveBankrupt", false);
        if (shouldRemove) {
            // This is likely what is breaking your signs!
            // If you delete the company, the signs have nothing to display.
            companiesService.deleteCompany(company.getId());
            playersService.cleanUpInvestmentsForOnlinePlayers(companiesService.getAllCompanies());
        } else {
            // Keep the company but mark price as 0
            company.setBankrupt(true);
            companiesService.updateCompanySharesValue(company.getId(), 0.0, -1.0);
        }
    }
}