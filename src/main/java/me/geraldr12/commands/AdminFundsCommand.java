package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.CompanyFundsActions;
import me.geraldr12.data.services.CompanyFundsService;
import me.geraldr12.utils.FormattingUtils;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /invest admin funds list|grant <id> <amount>|resolve <operation> paid|returned}.
 * A pending payout is resolved only by staff after checking balances and logs: "paid" when the gold reached
 * its destination, "returned" when it is still in no player's balance and must go back to the company.
 */
public class AdminFundsCommand {
    static final String PERMISSION = "stonks.admin.reconcile";
    private final Stonks plugin;

    public AdminFundsCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            tell(player, "You don't have permission to do that.");
            return true;
        }
        CompanyFundsService funds = plugin.getCompanyFundsService();
        if (funds == null || !funds.enabled()) {
            tell(player, plugin.getMessages().getMessageByKey("fundsDisabled"));
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase();
        switch (action) {
            case "list" -> list(player, funds);
            case "grant" -> grant(player, args);
            case "resolve" -> resolve(player, funds, args);
            default -> tell(player, "Usage: /invest admin funds <list|grant <ID> <amount>|resolve <operation> <paid|returned>>");
        }
        return true;
    }

    private void list(Player player, CompanyFundsService funds) {
        tell(player, "Company funds held: " + FormattingUtils.formatDouble(funds.totalHeld())
                + " (in " + FormattingUtils.formatDouble(funds.paidIn())
                + ", out " + FormattingUtils.formatDouble(funds.paidOut()) + ")");
        List<CompanyFundsService.Pending> pending = funds.pending();
        if (funds.needsReview() && pending.isEmpty()) {
            tell(player, "The funds ledger could not be read; payouts are paused. Check plugins/Stonks/funds/ledger.yml.");
        }
        for (CompanyFundsService.Pending p : pending) {
            tell(player, p.id() + " company=" + p.companyId() + " " + p.kind() + " "
                    + FormattingUtils.formatDouble(p.amount()) + (p.player() == null ? "" : " player=" + p.player()));
        }
        if (pending.isEmpty()) tell(player, "No pending company payouts.");
    }

    private void grant(Player player, String[] args) {
        long companyId;
        double amount;
        try {
            companyId = Long.parseLong(args[1]);
            amount = Double.parseDouble(args[2]);
        } catch (RuntimeException e) {
            tell(player, "Usage: /invest admin funds grant <ID> <amount>");
            return;
        }
        CompanyFundsActions.Result result = new CompanyFundsActions(plugin).grantFromTreasury(companyId, amount);
        tell(player, switch (result.status()) {
            case SUCCESS -> "Moved " + FormattingUtils.formatDouble(result.amount())
                    + " from the treasury into company " + companyId + ".";
            case INSUFFICIENT_PLAYER_BALANCE -> "The treasury account cannot cover that amount.";
            case BLOCKED_PENDING_REVIEW -> "This company has a pending payout; resolve it first.";
            case NEEDS_REVIEW -> "CRITICAL: the gold left the treasury but was not credited; see the server log.";
            case DISABLED -> "The treasury account is not configured.";
            default -> "Unable to grant to that company.";
        });
    }

    private void resolve(Player player, CompanyFundsService funds, String[] args) {
        if (args.length < 3) {
            tell(player, "Usage: /invest admin funds resolve <operation> <paid|returned>");
            return;
        }
        String operation = args[1];
        CompanyFundsService.Pending pending = funds.pending(operation);
        if (pending == null) {
            tell(player, "No pending operation " + operation + ".");
            return;
        }
        boolean ok = switch (args[2].toLowerCase()) {
            case "paid" -> funds.settle(operation);
            case "returned" -> funds.release(operation);
            default -> {
                tell(player, "Choose paid (the gold reached its destination) or returned (it goes back to the company).");
                yield false;
            }
        };
        if (ok) {
            plugin.getLogger().warning("Staff " + player.getName() + " resolved company funds operation "
                    + operation + " as " + args[2].toLowerCase() + ".");
            tell(player, "Resolved " + operation + ".");
        }
    }

    private void tell(Player player, String text) {
        player.sendMessage(plugin.getMessages().getPluginPrefix() + text);
    }
}
