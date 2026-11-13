package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.TreasuryService;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.MarketTransactionLocks;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CreateCommand implements MainCommand.SubCommand {

    private final Stonks plugin;
    private boolean sharePriceConfigWarningLogged;

    public CreateCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {

        Messages messages = plugin.getMessages();
        CompaniesService companiesService = plugin.getCompaniesService();
        PlayersService playersService = plugin.getPlayersService();
        Economy vaultEconomy = plugin.getEconomy();

        if (args.length < 4) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest company create [name] [risk] [shares] [price] [icon]");
            return true;
        }

        try {
            String companyName = args[0];
            int companyRisk = Integer.parseInt(args[1]);
            int companySharesAmount = Integer.parseInt(args[2]);
            double companySharePrice = Double.parseDouble(args[3]);
            Material companyIcon = args.length >= 5 ? Material.matchMaterial(args[4].toUpperCase()) : null;
            if (args.length >= 5 && companyIcon == null) {
                player.sendMessage(messages.getPluginPrefix() + "Invalid company icon.");
                return true;
            }

            if (companyName.isBlank() || companyRisk <= 0 || companySharesAmount <= 0
                    || !Double.isFinite(companySharePrice) || companySharePrice <= 0) {
                player.sendMessage(messages.getPluginPrefix() + "Invalid company values.");
                return true;
            }

            if (!isSharePriceAllowed(player, companySharePrice, messages)) {
                return true;
            }

            synchronized (MarketTransactionLocks.companyAllocation()) {
                if (companiesService.companyNameExists(companyName)) {
                    player.sendMessage(messages.getPluginPrefix() + "A company with that name already exists.");
                    return true;
                }
                double companyCreationTax = companiesService.getCompanyCreationTax(companySharesAmount, companySharePrice, companyRisk);
                if (!Double.isFinite(companyCreationTax) || companyCreationTax <= 0) {
                    player.sendMessage(messages.getPluginPrefix() + "Unable to calculate a valid creation tax.");
                    return true;
                }
                final int fractionalDigits;
                try {
                    fractionalDigits = vaultEconomy.fractionalDigits();
                } catch (RuntimeException e) {
                    player.sendMessage(messages.getPluginPrefix() + "Unable to calculate a valid creation tax.");
                    return true;
                }
                if (fractionalDigits < -1) {
                    player.sendMessage(messages.getPluginPrefix() + "Unable to calculate a valid creation tax.");
                    return true;
                }
                companyCreationTax = fractionalDigits == -1 ? companyCreationTax
                        : BigDecimal.valueOf(companyCreationTax).setScale(fractionalDigits, RoundingMode.DOWN).doubleValue();
                if (!Double.isFinite(companyCreationTax) || companyCreationTax <= 0) {
                    player.sendMessage(messages.getPluginPrefix() + "Unable to calculate a valid creation tax.");
                    return true;
                }
                MarketTransactionJournal journal = plugin.getTransactionJournal();
                if (!MarketTransactionLocks.isFinanciallyReady(plugin, journal, player.getUniqueId())) {
                    player.sendMessage(messages.getPluginPrefix()
                            + "Transactions are temporarily unavailable or require reconciliation; try again later.");
                    return true;
                }
                CompanyDao preview = CompanyDao.builder().name(companyName).risk(companyRisk)
                        .totalShares(companySharesAmount).availableShares(companySharesAmount)
                        .initialSharePrice(companySharePrice).currentSharePrice(companySharePrice)
                        .icon(companyIcon).ownerUuid(player.getUniqueId().toString())
                        .historic(new me.geraldr12.utils.SizedStack<>(500)).build();
                try (MarketTransactionLocks.Admission admission =
                             MarketTransactionLocks.tryAdmit(journal)) {
                    if (admission == null) {
                        player.sendMessage(messages.getPluginPrefix()
                                + "Transactions are temporarily unavailable or require reconciliation; try again later.");
                        return true;
                    }
                    CompanyDao reserved = companiesService.reserveCompanyId(preview);
                    if (reserved == null) {
                        player.sendMessage(messages.getPluginPrefix() + "Unable to reserve company ID.");
                        return true;
                    }
                    synchronized (MarketTransactionLocks.company(reserved.getId())) {
                        synchronized (MarketTransactionLocks.player(player.getUniqueId())) {
                            boolean reconciliationBlocked;
                            try {
                                reconciliationBlocked = MarketTransactionLocks.requiresReconciliation(
                                        player.getUniqueId(), reserved.getId())
                                        || plugin.getTransactionJournal() != journal;
                            } catch (RuntimeException e) {
                                reconciliationBlocked = true;
                            }
                            if (reconciliationBlocked) {
                                player.sendMessage(messages.getPluginPrefix()
                                        + "Transactions are temporarily unavailable or require reconciliation; try again later.");
                                return true;
                            }
                            MarketTransactionRecord record = new MarketTransactionRecord();
                            record.setTransactionId(java.util.UUID.randomUUID().toString());
                            record.setOperation("CREATE");
                            record.setPlayerUuid(player.getUniqueId().toString());
                            record.setCompanyId((long) reserved.getId());
                            record.setQuantity(companySharesAmount);
                            record.setValue(companyCreationTax);
                            record.setTimestamp(System.currentTimeMillis());
                            if (journal.prepare(record) == null) {
                                player.sendMessage(messages.getPluginPrefix() + "Unable to prepare the transaction; try again later.");
                                return true;
                            }
                            boolean hasBalance;
                            try {
                                hasBalance = vaultEconomy.has(player, companyCreationTax);
                            } catch (RuntimeException e) {
                                journal.abort(record, "Vault balance check failed before economy mutation: "
                                        + e.getClass().getSimpleName());
                                plugin.getLogger().log(java.util.logging.Level.WARNING,
                                        "Stonks create Vault balance check failed before withdrawal", e);
                                player.sendMessage(messages.getPluginPrefix()
                                        + "Unable to verify your balance; try again later.");
                                return true;
                            }
                            if (!hasBalance) {
                                journal.abort(record, "Insufficient balance");
                                player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(
                                        messages.getInsufficientMoney(), companyCreationTax));
                                return true;
                            }
                            EconomyResponse withdrawal;
                            try {
                                withdrawal = vaultEconomy.withdrawPlayer(player, companyCreationTax);
                            } catch (RuntimeException e) {
                                journal.markReconciliationRequired(record, "Vault withdrawal outcome is ambiguous: " + e.getClass().getSimpleName());
                                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                        "CRITICAL Stonks create Vault outcome is ambiguous", e);
                                player.sendMessage(messages.getPluginPrefix()
                                        + "CRITICAL: company creation requires administrator reconciliation.");
                                return true;
                            }
                            if (withdrawal == null || !withdrawal.transactionSuccess()
                                    || !Double.isFinite(withdrawal.amount)
                                    || Double.compare(withdrawal.amount, companyCreationTax) != 0) {
                                if (withdrawal != null && withdrawal.transactionSuccess()) {
                                    journal.markReconciliationRequired(record,
                                            "Vault withdrawal amount mismatch: expected " + companyCreationTax
                                                    + ", received " + withdrawal.amount);
                                    player.sendMessage(messages.getPluginPrefix()
                                            + "CRITICAL: company creation requires administrator reconciliation.");
                                    return true;
                                }
                                journal.abort(record, "Vault rejected withdrawal");
                                player.sendMessage(messages.getPluginPrefix() + "Unable to complete the payment.");
                                return true;
                            }
                            if (!journal.markEconomyApplied(record)) {
                                journal.markReconciliationRequired(record, "Economy applied but journal update failed");
                                player.sendMessage(messages.getPluginPrefix()
                                        + "CRITICAL: company creation requires administrator reconciliation.");
                                return true;
                            }
                            boolean companySaved = companiesService.saveReservedCompany(reserved);
                            if (!companySaved) {
                                journal.markReconciliationRequired(record, "Creation persistence failed after economy mutation: save");
                                player.sendMessage(messages.getPluginPrefix() + "CRITICAL: company creation state is inconsistent; contact an administrator.");
                                return true;
                            }
                            boolean companySharesRemoved = companiesService.removeSharesFromCompany(reserved.getId(), companySharesAmount);
                            if (!companySharesRemoved) {
                                journal.markReconciliationRequired(record, "Creation persistence failed after economy mutation: companyShares");
                                player.sendMessage(messages.getPluginPrefix() + "CRITICAL: company creation state is inconsistent; contact an administrator.");
                                return true;
                            }
                            boolean playerSharesAdded = playersService.addSharesToPlayer(player.getUniqueId(), reserved, companySharesAmount);
                            if (!playerSharesAdded) {
                                journal.markReconciliationRequired(record, "Creation persistence failed after economy mutation: playerShares");
                                player.sendMessage(messages.getPluginPrefix() + "CRITICAL: company creation state is inconsistent; contact an administrator.");
                                return true;
                            }
                            if (!journal.markStateCommitted(record)
                                    || !journal.markMarketImpactStarted(record)
                                    || !journal.markMarketImpactApplied(record)
                                    || !journal.complete(record)) {
                                journal.markReconciliationRequired(record,
                                        "Creation completion marker failed after state commit");
                                player.sendMessage(messages.getPluginPrefix()
                                        + "CRITICAL: company creation requires reconciliation.");
                                return true;
                            }
                            // Only the creation tax goes to the treasury; the valuation part is refunded on delete.
                            TreasuryService.credit(plugin.getTreasuryService(),
                                    companyCreationTax - companySharePrice * companySharesAmount);
                            player.sendMessage(messages.getPluginPrefix() + MessageFormat.format(messages.getCreatedCompany(), companyName));
                        }
                    }
                }
            }

        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Invalid numbers provided for risk, shares, or price.");
        }

        return true;
    }

    private boolean isSharePriceAllowed(Player player, double price, Messages messages) {
        FileConfiguration config = plugin.getConfig();
        Object minimum = config == null ? null : config.get("Stonks.Limits.MinSharePrice", 30);
        Object maximum = config == null ? null : config.get("Stonks.Limits.MaxSharePrice", 50);
        double min = minimum instanceof Number number ? number.doubleValue() : Double.NaN;
        double max = maximum instanceof Number number ? number.doubleValue() : Double.NaN;
        if (!Double.isFinite(min) || !Double.isFinite(max)
                || (min != -1 && max != -1 && min > max)) {
            if (!sharePriceConfigWarningLogged) {
                plugin.getLogger().warning("Invalid company share price limits; creation price limits are disabled.");
                sharePriceConfigWarningLogged = true;
            }
            return true;
        }
        if ((min != -1 && price < min) || (max != -1 && price > max)) {
            String low = BigDecimal.valueOf(min).stripTrailingZeros().toPlainString();
            String high = BigDecimal.valueOf(max).stripTrailingZeros().toPlainString();
            String rule = min == -1 ? "at most " + high : max == -1 ? "at least " + low
                    : "between " + low + " and " + high;
            player.sendMessage(messages.getPluginPrefix() + "Share price must be " + rule + ".");
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return IntStream.range(1, 6).mapToObj(String::valueOf).collect(Collectors.toList());
        } else if (args.length == 5) {
            return Arrays.stream(Material.values())
                    .map(Enum::name)
                    .filter(name -> name.toLowerCase().startsWith(args[4].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}