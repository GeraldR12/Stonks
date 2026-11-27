package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.economy.EconomyMutationOutcome;
import me.geraldr12.economy.EconomyMutationResult;
import me.geraldr12.economy.VaultEconomyMutationGateway;
import me.geraldr12.utils.MarketTransactionLocks;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * What players and staff do with a company's funds. Every movement takes gold from somewhere real (a player or the
 * treasury account) and puts it somewhere real; nothing here creates gold.
 */
public final class CompanyFundsActions {

    /** The economy side, so the rules can be tested without Vault. */
    public interface Wallet {
        int fractionalDigits();
        EconomyMutationResult withdraw(OfflinePlayer player, double amount);
        EconomyMutationResult deposit(OfflinePlayer player, double amount);
    }

    public enum Status {
        SUCCESS,
        DISABLED,
        NOT_OWNER,
        INVALID_AMOUNT,
        BLOCKED_PENDING_REVIEW,
        INSUFFICIENT_PLAYER_BALANCE,
        INSUFFICIENT_COMPANY_FUNDS,
        NO_SHAREHOLDERS,
        TOO_SMALL,
        REJECTED,
        NEEDS_REVIEW
    }

    /** {@code amount} is what moved; {@code paidHolders} counts dividend recipients. */
    public record Result(Status status, double amount, int paidHolders, String operationId) {
        static Result of(Status status) {
            return new Result(status, 0, 0, null);
        }
    }

    private final Stonks plugin;
    private final CompanyFundsService funds;
    private final Wallet wallet;
    private final Function<UUID, OfflinePlayer> players;

    public CompanyFundsActions(Stonks plugin) {
        this(plugin, plugin.getCompanyFundsService(), vault(plugin), Bukkit::getOfflinePlayer);
    }

    CompanyFundsActions(Stonks plugin, CompanyFundsService funds, Wallet wallet,
                        Function<UUID, OfflinePlayer> players) {
        this.plugin = plugin;
        this.funds = funds;
        this.wallet = wallet;
        this.players = players;
    }

    static Wallet vault(Stonks plugin) {
        return new Wallet() {
            private VaultEconomyMutationGateway gateway() {
                return new VaultEconomyMutationGateway(plugin.getEconomy());
            }

            @Override
            public int fractionalDigits() {
                return plugin.getEconomy() == null ? 0 : plugin.getEconomy().fractionalDigits();
            }

            @Override
            public EconomyMutationResult withdraw(OfflinePlayer player, double amount) {
                return gateway().withdraw(player, amount);
            }

            @Override
            public EconomyMutationResult deposit(OfflinePlayer player, double amount) {
                return gateway().deposit(player, amount);
            }
        };
    }

    /** The owner puts their own gold into the company. */
    public Result deposit(UUID owner, long companyId, double requested) {
        if (funds == null || !funds.enabled()) return Result.of(Status.DISABLED);
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = company(companyId);
            if (company == null || company.isBankrupt() || !isOwner(company, owner)) return Result.of(Status.NOT_OWNER);
            if (funds.hasPending(companyId)) return Result.of(Status.BLOCKED_PENDING_REVIEW);
            double amount = CompanyFundsService.floor(requested, wallet.fractionalDigits());
            if (!(amount > 0)) return Result.of(Status.INVALID_AMOUNT);
            String operationId = "DEPOSIT:" + UUID.randomUUID();
            EconomyMutationResult paid = call(() -> wallet.withdraw(players.apply(owner), amount), amount);
            if (paid.outcome() == EconomyMutationOutcome.DEFINITE_REJECTED) {
                return new Result(Status.INSUFFICIENT_PLAYER_BALANCE, amount, 0, operationId);
            }
            if (paid.outcome() != EconomyMutationOutcome.CONFIRMED) {
                severe("Deposit " + operationId + " of " + amount + " to company " + companyId
                        + " has an unconfirmed Vault outcome; nothing was credited.");
                return new Result(Status.NEEDS_REVIEW, amount, 0, operationId);
            }
            if (!funds.credit(companyId, amount, operationId)) {
                severe("Deposit " + operationId + ": " + amount + " gold left player " + owner
                        + " but could not be credited to company " + companyId + ".");
                return new Result(Status.NEEDS_REVIEW, amount, 0, operationId);
            }
            return new Result(Status.SUCCESS, amount, 0, operationId);
        }
    }

    /**
     * Pays {@code requested} out of the company's funds to every shareholder, per share. The share of each holder
     * is rounded down to what the economy can pay; the remainder stays in the company. A tax
     * ({@code Stonks.Taxes.Dividend}) goes to the treasury.
     */
    public Result dividend(UUID owner, long companyId, double requested) {
        if (funds == null || !funds.enabled()) return Result.of(Status.DISABLED);
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = company(companyId);
            if (company == null || company.isBankrupt() || !isOwner(company, owner)) return Result.of(Status.NOT_OWNER);
            if (funds.hasPending(companyId)) return Result.of(Status.BLOCKED_PENDING_REVIEW);
            int digits = wallet.fractionalDigits();
            double amount = CompanyFundsService.floor(requested, digits);
            if (!(amount > 0)) return Result.of(Status.INVALID_AMOUNT);
            if (amount > funds.balance(companyId)) {
                return new Result(Status.INSUFFICIENT_COMPANY_FUNDS, funds.balance(companyId), 0, null);
            }
            Map<UUID, Integer> holders = new TreeMap<>(plugin.getPlayersService().getShareholdersForCompany(companyId));
            long issued = holders.values().stream().mapToLong(Integer::longValue).sum();
            if (issued <= 0) return Result.of(Status.NO_SHAREHOLDERS);
            double tax = CompanyFundsService.floor(amount * dividendTax(), digits);
            double distributable = CompanyFundsService.add(amount, -tax);
            List<Map.Entry<UUID, Double>> payouts = new ArrayList<>();
            for (Map.Entry<UUID, Integer> holder : holders.entrySet()) {
                double share = CompanyFundsService.floor(distributable * holder.getValue() / issued, digits);
                if (share > 0) payouts.add(Map.entry(holder.getKey(), share));
            }
            if (payouts.isEmpty()) return Result.of(Status.TOO_SMALL);
            String batch = "DIVIDEND:" + UUID.randomUUID();
            if (tax > 0 && !toTreasury(companyId, tax, batch + ":tax")) {
                return new Result(Status.NEEDS_REVIEW, 0, 0, batch);
            }
            double paid = tax;
            int count = 0;
            for (Map.Entry<UUID, Double> payout : payouts) {
                String operationId = batch + ":" + payout.getKey();
                if (!funds.reserve(companyId, payout.getValue(), operationId, "DIVIDEND", payout.getKey().toString())) {
                    return new Result(Status.NEEDS_REVIEW, paid, count, batch);
                }
                EconomyMutationResult result = call(() -> wallet.deposit(players.apply(payout.getKey()),
                        payout.getValue()), payout.getValue());
                if (result.outcome() == EconomyMutationOutcome.CONFIRMED) {
                    funds.settle(operationId);
                    paid = CompanyFundsService.add(paid, payout.getValue());
                    count++;
                } else if (result.outcome() == EconomyMutationOutcome.DEFINITE_REJECTED) {
                    funds.release(operationId);
                } else {
                    severe("Dividend payout " + operationId + " has an unconfirmed Vault outcome;"
                            + " the company is paused and the rest of this dividend was not paid.");
                    return new Result(Status.NEEDS_REVIEW, paid, count, batch);
                }
            }
            return new Result(Status.SUCCESS, paid, count, batch);
        }
    }

    /**
     * A linked QuickShop shop sold items: part of what its owner received becomes the company's funds.
     * The shop owner is charged; if Vault refuses, nothing is credited.
     */
    public Result shopShare(UUID shopOwner, long companyId, double saleValue, String operationId) {
        if (funds == null || !funds.enabled() || shopOwner == null) return Result.of(Status.DISABLED);
        double rate = shopRevenueShare();
        double amount = CompanyFundsService.floor(saleValue * rate, wallet.fractionalDigits());
        if (!(amount > 0)) return Result.of(Status.TOO_SMALL);
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = company(companyId);
            if (company == null || company.isBankrupt()) return Result.of(Status.NOT_OWNER);
            if (funds.hasPending(companyId)) return Result.of(Status.BLOCKED_PENDING_REVIEW);
            if (funds.hasCredited(operationId)) return new Result(Status.SUCCESS, 0, 0, operationId);
            EconomyMutationResult taken = call(() -> wallet.withdraw(players.apply(shopOwner), amount), amount);
            if (taken.outcome() == EconomyMutationOutcome.DEFINITE_REJECTED) {
                return new Result(Status.REJECTED, amount, 0, operationId);
            }
            if (taken.outcome() != EconomyMutationOutcome.CONFIRMED) {
                severe("Shop share " + operationId + " for company " + companyId
                        + " has an unconfirmed Vault outcome; nothing was credited.");
                return new Result(Status.NEEDS_REVIEW, amount, 0, operationId);
            }
            if (!funds.credit(companyId, amount, operationId)) {
                severe("Shop share " + operationId + ": " + amount + " gold left " + shopOwner
                        + " but could not be credited to company " + companyId + ".");
                return new Result(Status.NEEDS_REVIEW, amount, 0, operationId);
            }
            return new Result(Status.SUCCESS, amount, 0, operationId);
        }
    }

    /** Staff moves gold from the treasury account into a company (for example to back old shares). */
    public Result grantFromTreasury(long companyId, double requested) {
        if (funds == null || !funds.enabled()) return Result.of(Status.DISABLED);
        TreasuryService treasury = plugin.getTreasuryService();
        if (treasury == null || !treasury.enabled()) return Result.of(Status.DISABLED);
        synchronized (MarketTransactionLocks.company(companyId)) {
            CompanyDao company = company(companyId);
            if (company == null || company.isBankrupt()) return Result.of(Status.NOT_OWNER);
            if (funds.hasPending(companyId)) return Result.of(Status.BLOCKED_PENDING_REVIEW);
            double amount = treasury.toEconomy(requested);
            if (!(amount > 0)) return Result.of(Status.INVALID_AMOUNT);
            if (!treasury.grant(amount)) return new Result(Status.INSUFFICIENT_PLAYER_BALANCE, amount, 0, null);
            String operationId = "GRANT:" + UUID.randomUUID();
            if (!funds.credit(companyId, amount, operationId)) {
                severe("Grant " + operationId + ": " + amount + " gold left the treasury but could not be credited"
                        + " to company " + companyId + ".");
                return new Result(Status.NEEDS_REVIEW, amount, 0, operationId);
            }
            return new Result(Status.SUCCESS, amount, 0, operationId);
        }
    }

    /**
     * Closes a company's funds (bankruptcy, staff removal, or what an owner's payout left): the whole balance goes
     * to the treasury, so investors lose their money but the gold stays in the economy.
     */
    public static double closeToTreasury(Stonks plugin, CompanyFundsService funds, long companyId, String reason) {
        if (funds == null || !funds.enabled()) return 0;
        String operationId = reason + ":" + companyId + ":" + UUID.randomUUID();
        double amount = funds.drain(companyId, operationId, "TREASURY");
        if (amount > 0) {
            TreasuryService.credit(plugin.getTreasuryService(), amount);
            if (!funds.settle(operationId)) {
                plugin.getLogger().severe("Company " + companyId + " funds moved to the treasury but operation "
                        + operationId + " could not be settled.");
            }
        }
        funds.remove(companyId);
        return amount;
    }

    private boolean toTreasury(long companyId, double amount, String operationId) {
        if (!funds.reserve(companyId, amount, operationId, "TREASURY", null)) return false;
        TreasuryService.credit(plugin.getTreasuryService(), amount);
        return funds.settle(operationId);
    }

    private double dividendTax() {
        double rate = plugin.getConfig() == null ? 0 : plugin.getConfig().getDouble("Stonks.Taxes.Dividend", 0.0);
        return Double.isFinite(rate) && rate >= 0 && rate < 1 ? rate : 0;
    }

    private double shopRevenueShare() {
        double rate = plugin.getConfig() == null ? 0
                : plugin.getConfig().getDouble("Stonks.CompanyFunds.ShopRevenueShare", 0.05);
        return Double.isFinite(rate) && rate >= 0 && rate <= 1 ? rate : 0;
    }

    private CompanyDao company(long companyId) {
        return plugin.getCompaniesService() == null ? null : plugin.getCompaniesService().getCompanyById(companyId);
    }

    private static boolean isOwner(CompanyDao company, UUID player) {
        return player != null && player.toString().equals(company.getOwnerUuid());
    }

    private EconomyMutationResult call(java.util.function.Supplier<EconomyMutationResult> mutation, double amount) {
        try {
            EconomyMutationResult result = mutation.get();
            return result != null ? result
                    : new EconomyMutationResult(EconomyMutationOutcome.AMBIGUOUS, amount, null, null, null, "no result");
        } catch (RuntimeException e) {
            return new EconomyMutationResult(EconomyMutationOutcome.AMBIGUOUS, amount, null, null, null,
                    e.getClass().getSimpleName());
        }
    }

    private void severe(String message) {
        if (plugin.getLogger() != null) plugin.getLogger().severe(message);
    }
}
