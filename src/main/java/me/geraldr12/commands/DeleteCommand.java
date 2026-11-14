package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class DeleteCommand implements MainCommand.SubCommand {

    private final Stonks plugin;

    public DeleteCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        Messages messages = plugin.getMessages();
        CompaniesService companies = plugin.getCompaniesService();
        PlayersService players = plugin.getPlayersService();
        Economy economy = plugin.getEconomy();

        if (args.length < 1) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest company delete [ID]");
            return true;
        }

        final long companyId;
        try {
            companyId = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Invalid Company ID.");
            return true;
        }
        if (companyId <= 0) {
            player.sendMessage(messages.getPluginPrefix() + "Invalid Company ID.");
            return true;
        }

        UUID playerId = player.getUniqueId();
        synchronized (MarketTransactionLocks.companyAllocation()) {
            synchronized (MarketTransactionLocks.company(companyId)) {
                synchronized (MarketTransactionLocks.player(playerId)) {
                    MarketTransactionJournal journal = plugin.getTransactionJournal();
                    if (!MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, companyId)) {
                        player.sendMessage(messages.getPluginPrefix()
                                + "Transactions are temporarily unavailable or require reconciliation; try again later.");
                        return true;
                    }
                    try (MarketTransactionLocks.Admission admission =
                                 MarketTransactionLocks.tryAdmit(journal)) {
                        if (admission == null) {
                            player.sendMessage(messages.getPluginPrefix()
                                    + "Transactions are temporarily unavailable or require reconciliation; try again later.");
                            return true;
                        }

                    CompanyDao company = companies.getCompanyById(companyId);
                    if (company == null) {
                        player.sendMessage(messages.getPluginPrefix() + messages.getInvalidCompany());
                        return true;
                    }
                    if ((company.getOwnerUuid() != null
                            && !playerId.toString().equals(company.getOwnerUuid()))
                            || !players.hasSharesInCompany(playerId, companyId, company.getTotalShares())) {
                        player.sendMessage(messages.getPluginPrefix() + messages.getCannotDeleteNotOwnedCompany());
                        return true;
                    }

                    double valuation = company.getTotalShares() * company.getCurrentSharePrice();
                    if (!Double.isFinite(valuation) || valuation <= 0) {
                        player.sendMessage(messages.getPluginPrefix()
                                + "Unable to calculate a valid company valuation.");
                        return true;
                    }

                    final int fractionalDigits;
                    try {
                        fractionalDigits = economy.fractionalDigits();
                    } catch (RuntimeException e) {
                        player.sendMessage(messages.getPluginPrefix()
                                + "Unable to calculate a valid company valuation.");
                        return true;
                    }
                    if (fractionalDigits < -1) {
                        player.sendMessage(messages.getPluginPrefix()
                                + "Unable to calculate a valid company valuation.");
                        return true;
                    }
                    valuation = fractionalDigits == -1 ? valuation
                            : BigDecimal.valueOf(valuation).setScale(fractionalDigits, RoundingMode.DOWN).doubleValue();
                    if (!Double.isFinite(valuation) || valuation <= 0) {
                        player.sendMessage(messages.getPluginPrefix()
                                + "Unable to calculate a valid company valuation.");
                        return true;
                    }

                    MarketTransactionRecord record = transaction(playerId, companyId,
                            company.getTotalShares(), valuation);
                    if (journal.prepare(record) == null) {
                        player.sendMessage(messages.getPluginPrefix()
                                + "Unable to prepare the transaction; try again later.");
                        return true;
                    }

                    EconomyResponse deposit;
                    try {
                        deposit = economy.depositPlayer(player, valuation);
                    } catch (RuntimeException e) {
                        journal.markReconciliationRequired(record, "Vault deposit outcome is ambiguous: " + e.getClass().getSimpleName());
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "CRITICAL Stonks delete Vault outcome is ambiguous", e);
                        return critical(player, messages);
                    }
                    if (deposit == null || !deposit.transactionSuccess()
                            || !Double.isFinite(deposit.amount)
                            || Double.compare(deposit.amount, valuation) != 0) {
                        if (deposit != null && deposit.transactionSuccess()) {
                            journal.markReconciliationRequired(record,
                                    "Vault deposit amount mismatch: expected " + valuation
                                            + ", received " + deposit.amount);
                            return critical(player, messages);
                        }
                        if (!journal.abort(record, "Vault rejected deposit")) {
                            plugin.getLogger().severe("Unable to persist aborted delete transaction");
                        }
                        player.sendMessage(messages.getPluginPrefix() + "Unable to complete the payment.");
                        return true;
                    }
                    if (!journal.markEconomyApplied(record)) {
                        journal.markReconciliationRequired(record,
                                "Economy applied but journal update failed");
                        return critical(player, messages);
                    }

                    if (!players.removeSharesFromPlayer(playerId, companyId, company.getTotalShares())) {
                        reconcile(journal, record, "Player persistence failed after economy mutation");
                        return critical(player, messages);
                    }
                    if (!companies.deleteCompany(companyId)) {
                        reconcile(journal, record, "Company persistence failed after economy mutation");
                        return critical(player, messages);
                    }

                    if (journal != null) {
                        if (!journal.markStateCommitted(record)
                                || !journal.markMarketImpactStarted(record)
                                || !journal.markMarketImpactApplied(record)
                                || !journal.complete(record)) {
                            journal.markReconciliationRequired(record,
                                    "Completion marker failed after deletion commit");
                            return critical(player, messages);
                        }
                    }
                    player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(
                            messages.getSoldActions(), company.getTotalShares(), valuation));
                    player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(
                            messages.getDeletedCompany(), company.getName()));
                    return true;
                    }
                }
            }
        }
    }

    private boolean critical(Player player, Messages messages) {
        player.sendMessage(messages.getPluginPrefix()
                + "CRITICAL: company deletion state is inconsistent; contact an administrator.");
        plugin.getLogger().severe("CRITICAL Stonks delete transaction requires reconciliation");
        return true;
    }

    private void reconcile(MarketTransactionJournal journal, MarketTransactionRecord record, String reason) {
        if (journal != null) journal.markReconciliationRequired(record, reason);
    }

    private MarketTransactionRecord transaction(UUID playerId, long companyId, int quantity, double value) {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(UUID.randomUUID().toString());
        record.setOperation("DELETE");
        record.setPlayerUuid(playerId.toString());
        record.setCompanyId(companyId);
        record.setQuantity(quantity);
        record.setValue(value);
        record.setTimestamp(System.currentTimeMillis());
        return record;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        Player player = (Player) sender;
        if (args.length == 1) {
            return plugin.getPlayersService().getInvestments(player.getUniqueId()).stream()
                    .filter(inv -> plugin.getCompaniesService().companyExists(inv.getCompanyId())
                            && plugin.getCompaniesService().getCompanyById(inv.getCompanyId()).getTotalShares()
                            <= inv.getSharesAmount())
                    .map(inv -> String.valueOf(inv.getCompanyId()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
