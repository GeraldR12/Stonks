package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.economy.EconomyMutationOutcome;
import me.geraldr12.economy.EconomyMutationResult;
import me.geraldr12.economy.VaultEconomyMutationGateway;
import me.geraldr12.persistence.MarketTransactionEffect;
import me.geraldr12.persistence.MarketTransactionEffectState;
import me.geraldr12.persistence.MarketTransactionEffectType;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.utils.MarketTransactionLocks;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.function.Supplier;

public final class BuyTransactionService {
    interface EconomyMutator {
        int fractionalDigits();
        default boolean has(OfflinePlayer player, double amount) {
            return true;
        }
        EconomyMutationResult withdraw(OfflinePlayer player, double amount);
        EconomyMutationResult deposit(OfflinePlayer player, double amount);
    }

    private final Stonks plugin;
    private final EconomyMutator economy;

    public BuyTransactionService(Stonks plugin) {
        this(plugin, new EconomyMutator() {
            private VaultEconomyMutationGateway gateway() {
                return new VaultEconomyMutationGateway(plugin.getEconomy());
            }

            @Override
            public int fractionalDigits() {
                VaultEconomyMutationGateway gateway = gateway();
                gateway.requirePrimaryThread();
                return plugin.getEconomy().fractionalDigits();
            }

            @Override
            public boolean has(OfflinePlayer player, double amount) {
                VaultEconomyMutationGateway gateway = gateway();
                gateway.requirePrimaryThread();
                return plugin.getEconomy().has(player, amount);
            }

            @Override
            public EconomyMutationResult withdraw(OfflinePlayer player, double amount) {
                return gateway().withdraw(player, amount);
            }

            @Override
            public EconomyMutationResult deposit(OfflinePlayer player, double amount) {
                return gateway().deposit(player, amount);
            }
        });
    }

    BuyTransactionService(Stonks plugin, EconomyMutator economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    public BuyTransactionResult execute(UUID playerId, OfflinePlayer economyActor,
                                        long companyId, int quantity) {
        if (playerId == null || economyActor == null || companyId < 0 || quantity <= 0) {
            return result(BuyTransactionResult.Status.VALIDATION_REJECTED,
                    companyId, quantity, null);
        }

        MarketTransactionJournal journal = plugin.getTransactionJournal();
        synchronized (MarketTransactionLocks.companyAllocation()) {
            synchronized (MarketTransactionLocks.company(companyId)) {
                synchronized (MarketTransactionLocks.player(playerId)) {
                    if (!MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, companyId)) {
                        return result(BuyTransactionResult.Status.BLOCKED_PENDING_REVIEW, companyId, quantity, null);
                    }
                    try (MarketTransactionLocks.Admission admission =
                                 MarketTransactionLocks.tryAdmit(journal)) {
                        if (admission == null) {
                            return result(BuyTransactionResult.Status.BLOCKED_PENDING_REVIEW, companyId, quantity, null);
                        }
                    CompanyDao company = plugin.getCompaniesService().getCompanyById(companyId);
                    if (company == null || company.isBankrupt() || quantity <= 0
                            || !Double.isFinite(company.getCurrentSharePrice())
                            || company.getCurrentSharePrice() <= 0) {
                        return result(BuyTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }
                    if (company.getAvailableShares() != -1 && company.getAvailableShares() < quantity) {
                        return result(BuyTransactionResult.Status.INSUFFICIENT_SHARES, companyId, quantity, null);
                    }
                    double unitPrice = company.getCurrentSharePrice();
                    int fractionalDigits = economy.fractionalDigits();
                    if (fractionalDigits < -1) {
                        return result(BuyTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }
                    CompaniesService companies = plugin.getCompaniesService();
                    double gross = MarketPricing.buyGross(unitPrice, quantity, companies.getMarketImpactPerShare());
                    double unroundedTotal = gross + MarketPricing.fee(gross,
                            companies.getTradingTax(), companies.getTradingMinimum());
                    if (!Double.isFinite(unroundedTotal) || unroundedTotal <= 0) {
                        return result(BuyTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }
                    double total = fractionalDigits == -1 ? unroundedTotal
                            : BigDecimal.valueOf(unroundedTotal).setScale(fractionalDigits, RoundingMode.DOWN).doubleValue();
                    if (!Double.isFinite(total) || total <= 0) {
                        return result(BuyTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }
                    if (!economy.has(economyActor, total)) {
                        return BuyTransactionResult.of(BuyTransactionResult.Status.INSUFFICIENT_FUNDS,
                                companyId, quantity, null, total);
                    }
                    // Effective cost per share (impact + fee + rounding): what the player actually paid.
                    double effectiveUnitPrice = total / quantity;
                    long quoteTimestamp = System.currentTimeMillis();
                    MarketTransactionRecord record = new MarketTransactionRecord();
                    record.setTransactionId(UUID.randomUUID().toString());
                    record.setOperation("BUY");
                    record.setPlayerUuid(playerId.toString());
                    record.setCompanyId(companyId);
                    record.setQuantity(quantity);
                    record.setValue(total);
                    record.setQuoteCompanyId(companyId);
                    record.setQuotePlayerUuid(playerId.toString());
                    record.setQuoteQuantity(quantity);
                    record.setQuoteUnitSharePrice(effectiveUnitPrice);
                    record.setQuoteTotalValue(total);
                    record.setQuoteTimestamp(quoteTimestamp);
                    record.setTimestamp(quoteTimestamp);
                    if (journal.prepareCurrentSchema(record) == null) {
                        return result(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, companyId, quantity, record);
                    }

                    EconomyMutationResult debit = invoke(journal, record, MarketTransactionEffectType.ECONOMY_DEBIT,
                            10, total, quantity, () -> economy.withdraw(economyActor, total));
                    if (debit == null) {
                        reconcile(journal, record, "Debit request or outcome persistence is unresolved");
                        return result(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, companyId, quantity, record);
                    }
                    if (debit.outcome() != EconomyMutationOutcome.CONFIRMED) {
                        if (debit.outcome() == EconomyMutationOutcome.AMBIGUOUS) {
                            reconcile(journal, record, "Vault withdrawal outcome is ambiguous");
                            return result(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, companyId, quantity, record);
                        }
                        if (journal.abort(record, "Vault rejected withdrawal")) {
                            return result(BuyTransactionResult.Status.PAYMENT_REJECTED, companyId, quantity, record);
                        }
                        return unresolvedAfterTerminalFailure(journal, record, companyId, quantity,
                                "Debit rejection could not be persisted as ABORTED");
                    }

                    ShareMutationResult removed = invokeCompanyRemoval(journal, record, companyId, quantity);
                    if (removed == null || removed.getOutcome() != ShareMutationOutcome.CONFIRMED) {
                        if (removed != null && removed.getOutcome() == ShareMutationOutcome.DEFINITE_REJECTED) {
                            if (refund(journal, record, economyActor, total, quantity)) {
                                return result(BuyTransactionResult.Status.COMPENSATED, companyId, quantity, record);
                            }
                        } else {
                            return reconciliationResult(journal, record, companyId, quantity,
                                    "Company removal outcome is ambiguous");
                        }
                        return result(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, companyId, quantity, record);
                    }
                    ShareMutationResult added = invokePlayerAdd(journal, record, playerId, companyId, quantity, effectiveUnitPrice);
                    if (added == null || added.getOutcome() != ShareMutationOutcome.CONFIRMED) {
                        if (added != null && added.getOutcome() == ShareMutationOutcome.DEFINITE_REJECTED) {
                            ShareMutationResult restored = invokeCompanyRestoration(journal, record, companyId,
                                    removed, quantity);
                            if (restored != null && restored.getOutcome() == ShareMutationOutcome.CONFIRMED
                                    && refund(journal, record, economyActor, total, quantity)) {
                                return result(BuyTransactionResult.Status.COMPENSATED, companyId, quantity, record);
                            }
                            if (restored == null || restored.getOutcome() == ShareMutationOutcome.AMBIGUOUS) {
                                return reconciliationResult(journal, record, companyId, quantity,
                                        "Company restoration outcome is ambiguous");
                            }
                        } else {
                            return reconciliationResult(journal, record, companyId, quantity,
                                    "Player add outcome is ambiguous");
                        }
                        return result(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, companyId, quantity, record);
                    }
                    if (!request(journal, record, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0, quantity)) {
                        reconcile(journal, record, "Market impact request could not be persisted");
                        return result(BuyTransactionResult.Status.COMMITTED_MARKET_RECONCILIATION, companyId, quantity, record);
                    }
                    boolean impact;
                    try {
                        impact = plugin.getCompaniesService().applyMarketImpact(playerId, companyId, quantity, true);
                    } catch (RuntimeException e) {
                        impact = false;
                    }
                    if (!recordOutcome(journal, record, MarketTransactionEffectType.MARKET_IMPACT,
                            impact ? MarketTransactionEffectState.CONFIRMED : MarketTransactionEffectState.AMBIGUOUS,
                            impact ? "confirmed" : "market impact outcome is ambiguous")) {
                        reconcile(journal, record, "Market impact outcome persistence failed");
                        return result(BuyTransactionResult.Status.COMMITTED_MARKET_RECONCILIATION, companyId, quantity, record);
                    }
                    if (!impact) {
                        reconcile(journal, record, "Market impact outcome is ambiguous");
                        return result(BuyTransactionResult.Status.COMMITTED_MARKET_RECONCILIATION, companyId, quantity, record);
                    }
                    if (!journal.complete(record)) {
                        return result(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, companyId, quantity, record);
                    }
                    // The fee part of what the player paid goes to the server treasury (if configured).
                    TreasuryService.credit(plugin.getTreasuryService(), total - gross);
                    return result(BuyTransactionResult.Status.SUCCESS, companyId, quantity, record);
                    }
                }
            }
        }
    }

    private ShareMutationResult invokeCompanyRemoval(MarketTransactionJournal journal, MarketTransactionRecord record, long id, int q) {
        if (!request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 20, 0.0, q)) return null;
        ShareMutationResult result;
        try {
            result = plugin.getCompaniesService().removeSharesFromCompanyTyped(id, q);
        } catch (RuntimeException e) {
            result = ShareMutationResult.ambiguous(ShareMutationOperation.COMPANY_REMOVE,
                    null, id, q, null, null, null, null, null, e.getClass().getSimpleName());
        }
        if (!recordOutcome(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, state(result), result.getDiagnostic())) {
            reconcile(journal, record, "Company removal outcome persistence failed");
            return null;
        }
        return result;
    }

    private ShareMutationResult invokeCompanyRestoration(MarketTransactionJournal journal, MarketTransactionRecord record,
                                                         long id, ShareMutationResult removed, int quantity) {
        if (!request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_RESTORATION, 40, 0.0, quantity)) return null;
        ShareMutationResult result;
        try {
            result = plugin.getCompaniesService().restoreSharesToCompanyTyped(id,
                    removed.companyRemovalEvidence());
        } catch (RuntimeException e) {
            result = ShareMutationResult.ambiguous(ShareMutationOperation.COMPANY_RESTORE,
                    null, id, quantity, null, null, null, null, null, e.getClass().getSimpleName());
        }
        if (!recordOutcome(journal, record, MarketTransactionEffectType.COMPANY_SHARE_RESTORATION,
                state(result), result.getDiagnostic())) {
            reconcile(journal, record, "Company restoration outcome persistence failed");
            return null;
        }
        return result;
    }

    private ShareMutationResult invokePlayerAdd(MarketTransactionJournal journal, MarketTransactionRecord record,
                                                UUID p, long id, int q, double price) {
        if (!request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_ADD, 30, 0.0, q)) return null;
        ShareMutationResult result;
        try {
            result = plugin.getPlayersService().addSharesToPlayerTyped(p, id, q, price,
                    plugin.getConfig().getInt("Stonks.Limits.MaxSharesPerPlayer"));
        } catch (RuntimeException e) {
            result = ShareMutationResult.ambiguous(ShareMutationOperation.PLAYER_ADD,
                    p, id, q, null, null, price, null, null, e.getClass().getSimpleName());
        }
        if (!recordOutcome(journal, record, MarketTransactionEffectType.PLAYER_SHARE_ADD,
                state(result), result.getDiagnostic())) {
            reconcile(journal, record, "Player add outcome persistence failed");
            return null;
        }
        return result;
    }

    private boolean refund(MarketTransactionJournal journal, MarketTransactionRecord record, OfflinePlayer player,
                           double amount, int q) {
        if (!request(journal, record, MarketTransactionEffectType.ECONOMY_REFUND, 60, amount, q)) {
            reconcile(journal, record, "Refund request could not be persisted");
            return false;
        }
        EconomyMutationResult result;
        try {
            result = economy.deposit(player, amount);
        } catch (RuntimeException e) {
            reconcile(journal, record, "Refund outcome is ambiguous");
            return false;
        }
        if (!recordOutcome(journal, record, MarketTransactionEffectType.ECONOMY_REFUND,
                result.outcome() == EconomyMutationOutcome.CONFIRMED ? MarketTransactionEffectState.CONFIRMED : MarketTransactionEffectState.AMBIGUOUS,
                result.diagnostic())) {
            reconcile(journal, record, "Refund outcome persistence failed");
            return false;
        }
        if (result.outcome() != EconomyMutationOutcome.CONFIRMED) {
            reconcile(journal, record, "Refund outcome is ambiguous");
            return false;
        }
        if (journal.saveCompensated(record)) return true;
        reconcile(journal, record, "Compensation terminal persistence failed");
        return false;
    }

    private BuyTransactionResult unresolvedAfterTerminalFailure(MarketTransactionJournal journal,
                                                                 MarketTransactionRecord record,
                                                                 long companyId, int quantity,
                                                                 String reason) {
        if (journal.markReconciliationRequired(record, reason)) {
            return result(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, companyId, quantity, record);
        }
        return result(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, companyId, quantity, record);
    }

    private EconomyMutationResult invoke(MarketTransactionJournal j, MarketTransactionRecord r, MarketTransactionEffectType t,
                                         int seq, double amount, int q, Supplier<EconomyMutationResult> call) {
        if (!request(j, r, t, seq, amount, q)) return null;
        try {
            EconomyMutationResult result = call.get();
            if (!recordOutcome(j, r, t, result.outcome() == EconomyMutationOutcome.CONFIRMED
                    ? MarketTransactionEffectState.CONFIRMED
                    : result.outcome() == EconomyMutationOutcome.DEFINITE_REJECTED
                    ? MarketTransactionEffectState.DEFINITE_REJECTED : MarketTransactionEffectState.AMBIGUOUS,
                    result.diagnostic())) {
                reconcile(j, r, "Economy outcome persistence failed");
                return null;
            }
            return result;
        } catch (RuntimeException e) {
            reconcile(j, r, "Economy outcome is ambiguous");
            return null;
        }
    }

    private BuyTransactionResult result(BuyTransactionResult.Status status, long companyId, int quantity,
                                        MarketTransactionRecord record) {
        return BuyTransactionResult.of(status, companyId, quantity,
                record == null ? null : record.getTransactionId());
    }

    private boolean request(MarketTransactionJournal j, MarketTransactionRecord r, MarketTransactionEffectType t,
                            int seq, double amount, int q) {
        return j.requestEffect(r, t, seq, amount, q);
    }

    private boolean recordOutcome(MarketTransactionJournal j, MarketTransactionRecord r, MarketTransactionEffectType t,
                                  MarketTransactionEffectState s, String reason) {
        return j.recordEffectOutcome(r, effectId(r, t), s, reason);
    }

    private String effectId(MarketTransactionRecord r, MarketTransactionEffectType t) {
        return r.getEffects().stream().filter(e -> e.getType() == t)
                .map(MarketTransactionEffect::getEffectId).findFirst().orElse(null);
    }

    private MarketTransactionEffectState state(ShareMutationResult r) {
        return r.getOutcome() == ShareMutationOutcome.CONFIRMED ? MarketTransactionEffectState.CONFIRMED
                : r.getOutcome() == ShareMutationOutcome.DEFINITE_REJECTED ? MarketTransactionEffectState.DEFINITE_REJECTED
                : MarketTransactionEffectState.AMBIGUOUS;
    }

    private BuyTransactionResult reconciliationResult(MarketTransactionJournal journal,
                                                       MarketTransactionRecord record,
                                                       long companyId, int quantity,
                                                       String reason) {
        if (journal.markReconciliationRequired(record, reason)) {
            return result(BuyTransactionResult.Status.RECONCILIATION_REQUIRED,
                    companyId, quantity, record);
        }
        return result(BuyTransactionResult.Status.CRITICAL_UNRESOLVED,
                companyId, quantity, record);
    }

    private void reconcile(MarketTransactionJournal j, MarketTransactionRecord r, String reason) {
        j.markReconciliationRequired(r, reason);
    }
}
