package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.BuyTransactionResult;
import me.geraldr12.data.services.BuyTransactionService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.List;
import java.util.stream.Collectors;

public class BuyCommand implements MainCommand.SubCommand {
    @FunctionalInterface
    interface BuyExecutor {
        BuyTransactionResult execute(java.util.UUID playerId, Player player, long companyId, int quantity);
    }

    private final Stonks plugin;
    private final BuyExecutor transactions;

    public BuyCommand(Stonks plugin) {
        this(plugin, new BuyTransactionService(plugin)::execute);
    }

    BuyCommand(Stonks plugin, BuyExecutor transactions) {
        this.plugin = plugin;
        this.transactions = transactions;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().getPluginPrefix() + "Usage: /invest buy <id> <amount>");
            return true;
        }
        try {
            long companyId = Long.parseLong(args[0]);
            int shares = Integer.parseInt(args[1]);
            if (companyId < 0 || shares <= 0) {
                player.sendMessage(plugin.getMessages().getPluginPrefix() + "Error: ID and Amount must be positive.");
                return true;
            }
            BuyTransactionResult result = transactions.execute(player.getUniqueId(), player, companyId, shares);
            player.sendMessage(plugin.getMessages().getPluginPrefix() + message(result));
            return true;
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessages().getPluginPrefix() + "Error: ID and Amount must be numbers.");
            return true;
        }
    }

    private String message(BuyTransactionResult result) {
        return switch (result.status()) {
            case SUCCESS -> MessageFormat.format(plugin.getMessages().getBoughtActions(), result.quantity());
            case VALIDATION_REJECTED -> plugin.getMessages().getInvalidCompany();
            case INSUFFICIENT_FUNDS -> MessageFormat.format(plugin.getMessages().getInsufficientMoney(),
                    me.geraldr12.utils.FormattingUtils.formatDouble(result.totalValue()));
            case INSUFFICIENT_SHARES -> plugin.getMessages().getInsufficientActions();
            case PAYMENT_REJECTED -> "Unable to complete the payment.";
            case COMPENSATED -> "Purchase could not be completed; your payment was refunded.";
            case COMMITTED_MARKET_RECONCILIATION -> "Purchase completed; market update requires administrator reconciliation.";
            case BLOCKED_PENDING_REVIEW -> "Trading is paused for this company or your account: an earlier transaction is awaiting administrator review. Nothing was charged.";
            case RECONCILIATION_REQUIRED, CRITICAL_UNRESOLVED -> "CRITICAL: purchase state requires administrator reconciliation.";
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
