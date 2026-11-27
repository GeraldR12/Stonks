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
            player.sendMessage(plugin.getMessages().getPluginPrefix()
                    + (result.status() == BuyTransactionResult.Status.SHARE_LIMIT_EXCEEDED
                    ? shareLimitMessage(player) : message(result)));
            return true;
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessages().getPluginPrefix() + "Error: ID and Amount must be numbers.");
            return true;
        }
    }

    private String shareLimitMessage(Player player) {
        int max = plugin.getConfig().getInt("Stonks.Limits.MaxSharesPerPlayer");
        long counted = plugin.getPlayersService().getSharesCountedTowardsLimit(player.getUniqueId());
        long room = Math.max(0, max - counted);
        return "You can own at most " + max + " shares of companies you don't own, and you have " + counted
                + ". You can buy " + room + " more. Shares of your own companies don't count. Nothing was charged.";
    }

    private String message(BuyTransactionResult result) {
        return switch (result.status()) {
            case SHARE_LIMIT_EXCEEDED -> "You've reached the share limit. Nothing was charged.";
            case SUCCESS -> MessageFormat.format(plugin.getMessages().getBoughtActions(), result.quantity());
            case QUOTE_CHANGED -> plugin.getMessages().getMessageByKey("uiQuoteChanged");
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

    public boolean confirm(Player player, long companyId, int quantity,
                           me.geraldr12.data.services.MarketPricing.Quote quote) {
        var result = new BuyTransactionService(plugin).execute(player.getUniqueId(), player, companyId, quantity, quote);
        player.sendMessage(plugin.getMessages().getPluginPrefix()
                + (result.status() == BuyTransactionResult.Status.SHARE_LIMIT_EXCEEDED
                ? shareLimitMessage(player) : message(result)));
        return result.status() != BuyTransactionResult.Status.QUOTE_CHANGED;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return plugin.getCompaniesService().getAllCompanies().stream()
                    .filter(me.geraldr12.data.dao.CompanyDao::isTrading)
                    .map(company -> String.valueOf(company.getId()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) return List.of("1", "10", "100");
        return List.of();
    }
}
