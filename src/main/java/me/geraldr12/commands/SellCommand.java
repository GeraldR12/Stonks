package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.SellTransactionResult;
import me.geraldr12.data.services.SellTransactionService;
import me.geraldr12.utils.Messages;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class SellCommand implements MainCommand.SubCommand {

    @FunctionalInterface
    interface SellExecutor {
        SellTransactionResult execute(UUID playerId, OfflinePlayer economyActor, long companyId, int quantity);
    }

    private final Stonks plugin;
    private final SellExecutor transactions;

    public SellCommand(Stonks plugin) {
        this(plugin, new SellTransactionService(plugin)::execute);
    }

    SellCommand(Stonks plugin, SellExecutor transactions) {
        this.plugin = plugin;
        this.transactions = transactions;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        Messages messages = plugin.getMessages();
        if (args.length < 2) {
            player.sendMessage(messages.getPluginPrefix() + "Usage: /invest sell <id> <amount>");
            return true;
        }

        try {
            long companyId = Long.parseLong(args[0]);
            int quantity = Integer.parseInt(args[1]);
            SellTransactionResult result = transactions.execute(
                    player.getUniqueId(), player, companyId, quantity);
            player.sendMessage(messages.getPluginPrefix() + message(result));
            return true;
        } catch (NumberFormatException e) {
            player.sendMessage(messages.getPluginPrefix() + "Error: ID and Amount must be numbers.");
            return true;
        }
    }

    private String message(SellTransactionResult result) {
        return switch (result.status()) {
            case SUCCESS -> MessageFormat.format(plugin.getMessages().getSoldActions(), result.quantity(),
                    Double.isFinite(result.totalValue())
                            ? me.geraldr12.utils.FormattingUtils.formatDouble(result.totalValue()) : "?");
            case VALIDATION_REJECTED -> plugin.getMessages().getInsufficientActions();
            case INSUFFICIENT_SHARES -> plugin.getMessages().getPlayerNoActions();
            case FEE_EXCEEDS_VALUE -> "This sale is too small: its value does not cover the minimum trading fee.";
            case DEFINITE_FAILURE -> "Unable to complete the sale.";
            case COMPENSATED -> "Sale could not be completed; your shares were restored.";
            case BLOCKED_PENDING_REVIEW -> "Trading is paused for this company or your account: an earlier transaction is awaiting administrator review. Nothing was charged.";
            case RECONCILIATION_REQUIRED -> "CRITICAL: sale state requires administrator reconciliation.";
            case MARKET_IMPACT_FAILURE_AFTER_COMMIT ->
                    "Sale completed; market update requires administrator reconciliation.";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return plugin.getCompaniesService().getAllCompanies().stream()
                    .map(company -> String.valueOf(company.getId()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) return List.of("1", "10", "100");
        return List.of();
    }
}
