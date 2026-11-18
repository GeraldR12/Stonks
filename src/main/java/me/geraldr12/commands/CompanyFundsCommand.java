package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompanyFundsActions;
import me.geraldr12.data.services.CompanyFundsService;
import me.geraldr12.utils.FormattingUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.List;
import java.util.UUID;

/**
 * {@code /invest company funds <id>}, {@code deposit <id> <amount>} and {@code dividend <id> <amount>}.
 * Owners can add gold and pay dividends; there is no personal withdrawal, so a company cannot be emptied
 * at its investors' expense.
 */
public class CompanyFundsCommand {
    private final Stonks plugin;

    public CompanyFundsCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    public boolean funds(Player player, String[] args) {
        Long companyId = companyId(player, args, "/invest company funds <ID>");
        if (companyId == null) return true;
        CompanyFundsService funds = plugin.getCompanyFundsService();
        CompanyDao company = plugin.getCompaniesService().getCompanyById(companyId);
        if (funds == null || !funds.enabled()) {
            tell(player, message("fundsDisabled"));
            return true;
        }
        if (company == null) {
            tell(player, plugin.getMessages().getInvalidCompany());
            return true;
        }
        tell(player, MessageFormat.format(message("fundsBalance"), company.getName(),
                FormattingUtils.formatDouble(funds.balance(companyId))));
        if (funds.hasPending(companyId)) tell(player, message("fundsPendingReview"));
        return true;
    }

    public boolean deposit(Player player, String[] args) {
        Long companyId = companyId(player, args, "/invest company deposit <ID> <amount>");
        Double amount = amount(player, args, "/invest company deposit <ID> <amount>");
        if (companyId == null || amount == null) return true;
        CompanyFundsActions.Result result = new CompanyFundsActions(plugin)
                .deposit(player.getUniqueId(), companyId, amount);
        tell(player, switch (result.status()) {
            case SUCCESS -> MessageFormat.format(message("fundsDeposited"),
                    FormattingUtils.formatDouble(result.amount()), balance(companyId));
            case INSUFFICIENT_PLAYER_BALANCE -> MessageFormat.format(plugin.getMessages().getInsufficientMoney(),
                    result.amount());
            default -> common(result);
        });
        return true;
    }

    public boolean dividend(Player player, String[] args) {
        Long companyId = companyId(player, args, "/invest company dividend <ID> <amount>");
        Double amount = amount(player, args, "/invest company dividend <ID> <amount>");
        if (companyId == null || amount == null) return true;
        CompanyFundsActions.Result result = new CompanyFundsActions(plugin)
                .dividend(player.getUniqueId(), companyId, amount);
        tell(player, switch (result.status()) {
            case SUCCESS -> MessageFormat.format(message("fundsDividendPaid"),
                    FormattingUtils.formatDouble(result.amount()), result.paidHolders(), balance(companyId));
            case INSUFFICIENT_COMPANY_FUNDS -> MessageFormat.format(message("fundsInsufficient"),
                    FormattingUtils.formatDouble(result.amount()));
            case NO_SHAREHOLDERS, TOO_SMALL -> message("fundsDividendTooSmall");
            default -> common(result);
        });
        return true;
    }

    private String common(CompanyFundsActions.Result result) {
        return switch (result.status()) {
            case DISABLED -> message("fundsDisabled");
            case NOT_OWNER -> plugin.getMessages().getCannotDeleteNotOwnedCompany();
            case INVALID_AMOUNT -> message("fundsInvalidAmount");
            case BLOCKED_PENDING_REVIEW -> message("fundsPendingReview");
            case NEEDS_REVIEW -> message("fundsNeedsReview");
            default -> message("fundsRejected");
        };
    }

    private String balance(long companyId) {
        return FormattingUtils.formatDouble(plugin.getCompanyFundsService().balance(companyId));
    }

    private Long companyId(Player player, String[] args, String usage) {
        try {
            long id = Long.parseLong(args.length > 0 ? args[0] : "");
            if (id >= 0) return id;
        } catch (NumberFormatException ignored) {
        }
        tell(player, "Usage: " + usage);
        return null;
    }

    private Double amount(Player player, String[] args, String usage) {
        try {
            double value = Double.parseDouble(args.length > 1 ? args[1] : "");
            if (Double.isFinite(value) && value > 0) return value;
        } catch (NumberFormatException ignored) {
        }
        tell(player, "Usage: " + usage);
        return null;
    }

    private String message(String key) {
        return plugin.getMessages().getMessageByKey(key);
    }

    private void tell(Player player, String text) {
        player.sendMessage(plugin.getMessages().getPluginPrefix() + text);
    }

    public List<String> ownedCompanyIds(CommandSender sender) {
        if (!(sender instanceof Player player)) return List.of();
        UUID id = player.getUniqueId();
        return plugin.getCompaniesService().getAllCompanies().stream()
                .filter(c -> id.toString().equals(c.getOwnerUuid()) && !c.isBankrupt())
                .map(c -> String.valueOf(c.getId())).toList();
    }
}
