package me.geraldr12.schedulers;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.QuoteDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PriceHistoryLog;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.enums.NotificationType;
import me.geraldr12.events.CompanyStockUpdateEvent;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.SizedStack;
import me.geraldr12.events.CompanyBankruptEvent;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.random.StocksRandomizer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import javax.inject.Inject;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

public class InterestRateScheduler extends BukkitRunnable {

    private final Stonks plugin;
    private final CompaniesService companiesService;
    private final SignsService signsService;
    private final PlayersService playersService;
    private final Messages messages;
    private boolean bankruptcyRemovalWarningLogged;

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
            try {
                processCompany(company);
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Interest-rate update failed for company " + company.getId(), e);
            }
        }

        broadcastUpdate();
    }

    private boolean isFinanciallyReady(MarketTransactionJournal journal) {
        try {
            return MarketTransactionLocks.isJournalLifecycleReady(plugin, journal);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void processCompany(CompanyDao company) {
        MarketTransactionJournal journal;
        try {
            journal = plugin == null ? null : plugin.getTransactionJournal();
        } catch (RuntimeException exception) {
            return;
        }
        if (!isFinanciallyReady(journal)) {
            return;
        }

        try (MarketTransactionLocks.Admission admission = MarketTransactionLocks.tryAdmit(journal)) {
            if (admission == null) {
                return;
            }

            CompanyDao completedCompany;
            boolean bankrupt;
            double oldSharePrice;
            double newSharePrice;
            double newSharesQuote;
            synchronized (MarketTransactionLocks.company(company.getId())) {
                CompanyDao current = companiesService.getCompanyById(company.getId());
                if (current == null || current.isBankrupt()) {
                    if (current != null && current.isBankrupt()) {
                        updateSigns(current.getId());
                    }
                    return;
                }

                oldSharePrice = current.getCurrentSharePrice();
                StocksRandomizer stocksRandomizer = new StocksRandomizer(
                        current.getRisk(), current.getInitialSharePrice());
                newSharesQuote = stocksRandomizer.getRandomQuote(oldSharePrice);
                newSharePrice = stocksRandomizer.getRandomStockValue(oldSharePrice, newSharesQuote);
                if (shouldCrash(current.getRisk(), stocksRandomizer, newSharePrice)) {
                    newSharePrice = 0;
                }

                SizedStack<QuoteDao> history = current.getHistoric() == null
                        ? new SizedStack<>(500)
                        : current.getHistoric().fromList(new ArrayList<>(current.getHistoric())); // oldest first, as stored
                history.push(new QuoteDao(newSharesQuote, newSharePrice, System.currentTimeMillis()));
                CompanyDao snapshot = CompanyDao.builder()
                        .id(current.getId())
                        .name(current.getName())
                        .description(current.getDescription())
                        .icon(current.getIcon())
                        .initialSharePrice(current.getInitialSharePrice())
                        .currentSharePrice(newSharePrice)
                        .risk(current.getRisk())
                        .totalShares(current.getTotalShares())
                        .availableShares(current.getAvailableShares())
                        .historic(history)
                        .ownerUuid(current.getOwnerUuid())
                        .bankrupt(newSharePrice <= 0 || current.isBankrupt())
                        .build();

                if (!companiesService.persistSchedulerSnapshot(snapshot)) {
                    throw new IllegalStateException("Unable to persist scheduler company snapshot");
                }
                completedCompany = snapshot;
                bankrupt = snapshot.isBankrupt();
            }

            PriceHistoryLog.record(plugin.getPriceHistoryLog(), completedCompany.getId(), completedCompany.getName(),
                    completedCompany.getRisk(), bankrupt ? PriceHistoryLog.Source.CRASH : PriceHistoryLog.Source.UPDATE,
                    0, oldSharePrice, newSharePrice,
                    oldSharePrice > 0 ? (newSharePrice - oldSharePrice) / oldSharePrice : 0);

            if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
                plugin.getServer().getPluginManager().callEvent(new CompanyStockUpdateEvent(
                        completedCompany, oldSharePrice, newSharePrice, newSharesQuote));
            }
            if (bankrupt) {
                if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
                    plugin.getServer().getPluginManager().callEvent(new CompanyBankruptEvent(completedCompany));
                }
                if (plugin.getServer() != null) {
                    plugin.getServer().getOnlinePlayers().stream()
                            .filter(player -> playersService.hasNotificationEnabled(
                                    player.getUniqueId(), NotificationType.COMPANY_BANKRUPT))
                            .forEach(player -> player.sendMessage(messages.getPluginPrefix()
                                    + MessageFormat.format(messages.getCompanyStocksCrashed(), completedCompany.getName())));
                }
            }
            updateSigns(company.getId());
        }
    }

    private void handleBankruptcy(CompanyDao company) {
        processBankruptCompany(company);

        CompanyDao bankruptCompany = companiesService.getCompanyById(company.getId());
        if (bankruptCompany == null || !bankruptCompany.isBankrupt()
                || bankruptCompany.getCurrentSharePrice() != 0.0) {
            throw new IllegalStateException("Unable to verify bankrupt company state");
        }

        CompanyBankruptEvent bankruptEvent = new CompanyBankruptEvent(bankruptCompany);
        Bukkit.getPluginManager().callEvent(bankruptEvent);

        plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> playersService.hasNotificationEnabled(player.getUniqueId(), NotificationType.COMPANY_BANKRUPT))
                .forEach(player -> player.sendMessage(messages.getPluginPrefix() +
                        MessageFormat.format(messages.getCompanyStocksCrashed(), bankruptCompany.getName())));
    }

    private void updateSigns(long companyId) {
        signsService.updateBukkitSignsByCompany(companyId);
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
        if (shouldRemove && !bankruptcyRemovalWarningLogged) {
            plugin.getLogger().warning(
                    "Automatic bankrupt-company removal is disabled until durable all-shareholder settlement is implemented.");
            bankruptcyRemovalWarningLogged = true;
        }

        if (!companiesService.markCompanyBankrupt(company.getId())) {
            throw new IllegalStateException("Unable to persist bankrupt company state");
        }
    }
}