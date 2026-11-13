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

public final class SellTransactionService {
    interface EconomyMutator {
        int fractionalDigits();
        EconomyMutationResult deposit(OfflinePlayer player, double amount);
    }

    private final Stonks plugin;
    private final EconomyMutator economy;

    public SellTransactionService(Stonks plugin) {
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
            public EconomyMutationResult deposit(OfflinePlayer player, double amount) {
                return gateway().deposit(player, amount);
            }
        });
    }

    SellTransactionService(Stonks plugin, EconomyMutator economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    public SellTransactionResult execute(UUID playerId, OfflinePlayer economyActor,
                                         long companyId, int quantity) {
        if (playerId == null || economyActor == null || companyId < 0 || quantity <= 0) {
            return result(SellTransactionResult.Status.VALIDATION_REJECTED,
                    Math.max(0, companyId), Math.max(1, quantity), null);
        }

        MarketTransactionJournal journal = plugin.getTransactionJournal();

        synchronized (MarketTransactionLocks.companyAllocation()) {
            synchronized (MarketTransactionLocks.company(companyId)) {
                synchronized (MarketTransactionLocks.player(playerId)) {
                    if (!MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, companyId)) {
                        return result(SellTransactionResult.Status.BLOCKED_PENDING_REVIEW, companyId, quantity, null);
                    }
                    try (MarketTransactionLocks.Admission admission =
                                 MarketTransactionLocks.tryAdmit(journal)) {
                        if (admission == null) {
                            return result(SellTransactionResult.Status.BLOCKED_PENDING_REVIEW, companyId, quantity, null);
                        }
                    CompanyDao company = plugin.getCompaniesService().getCompanyById(companyId);
                    if (company == null || company.isBankrupt()) {
                        return result(SellTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }

                    double rawUnitPrice = company.getCurrentSharePrice();
                    if (!Double.isFinite(rawUnitPrice) || rawUnitPrice <= 0) {
                        return result(SellTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }

                    double unitPrice = rawUnitPrice;
                    int fractionalDigits = economy.fractionalDigits();
                    if (fractionalDigits < -1) {
                        return result(SellTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }
                    CompaniesService companies = plugin.getCompaniesService();
                    double gross = MarketPricing.sellGross(unitPrice, quantity, companies.getMarketImpactPerShare());
                    double unroundedTotal = gross - MarketPricing.fee(gross,
                            companies.getTradingTax(), companies.getTradingMinimum());
                    if (!Double.isFinite(unroundedTotal)) {
                        return result(SellTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }
                    if (unroundedTotal <= 0) {
                        return result(SellTransactionResult.Status.FEE_EXCEEDS_VALUE, companyId, quantity, null);
                    }
                    double total = fractionalDigits == -1 ? unroundedTotal
                            : BigDecimal.valueOf(unroundedTotal).setScale(fractionalDigits, RoundingMode.DOWN).doubleValue();
                    if (!Double.isFinite(total)) {
                        return result(SellTransactionResult.Status.VALIDATION_REJECTED, companyId, quantity, null);
                    }
                    if (total <= 0) {
                        return result(SellTransactionResult.Status.FEE_EXCEEDS_VALUE, companyId, quantity, null);
                    }

                    // Verify sufficient player shares
                    long currentShares = plugin.getPlayersService().getInvestmentInCompany(playerId, companyId)
                        .map(i -> (long) i.getSharesAmount())
                        .orElse(0L);
                    if (currentShares < quantity) {
                        return result(SellTransactionResult.Status.INSUFFICIENT_SHARES, companyId, quantity, null);
                    }

                    long quoteTimestamp = System.currentTimeMillis();

                    MarketTransactionRecord record = new MarketTransactionRecord();
                    record.setTransactionId(UUID.randomUUID().toString());
                    record.setOperation("SELL");
                    record.setPlayerUuid(playerId.toString());
                    record.setCompanyId(companyId);
                    record.setQuantity(quantity);
                    record.setValue(total);
                    record.setQuoteCompanyId(companyId);
                    record.setQuotePlayerUuid(playerId.toString());
                    record.setQuoteQuantity(quantity);
                    record.setQuoteUnitSharePrice(total / quantity); // effective value received per share
                    record.setQuoteTotalValue(total);
                    record.setQuoteTimestamp(quoteTimestamp);
                    record.setTimestamp(quoteTimestamp);

                    if (journal.prepareCurrentSchema(record) == null) {
                        return result(SellTransactionResult.Status.RECONCILIATION_REQUIRED, companyId, quantity, null);
                    }

                    // 10 PLAYER_SHARE_REMOVAL
                    ShareMutationResult playerRemoval = invokePlayerRemoval(journal, record, playerId, companyId, quantity);
                    if (playerRemoval == null || playerRemoval.getOutcome() != ShareMutationOutcome.CONFIRMED) {
                        if (playerRemoval != null && playerRemoval.getOutcome() == ShareMutationOutcome.DEFINITE_REJECTED) {
                            if (journal.abort(record, "Player shares removal rejected")) {
                                return result(SellTransactionResult.Status.DEFINITE_FAILURE, companyId, quantity, record);
                            }
                            return reconciliationResult(journal, record, companyId, quantity,
                                    "Player removal rejection could not be persisted as ABORTED");
                        }
                        return reconciliationResult(journal, record, companyId, quantity, "Player removal outcome is ambiguous");
                    }

                    // 20 COMPANY_SHARE_ADD
                    ShareMutationResult companyAdd = invokeCompanyAdd(journal, record, companyId, quantity);
                    if (companyAdd == null || companyAdd.getOutcome() != ShareMutationOutcome.CONFIRMED) {
                        if (companyAdd != null && companyAdd.getOutcome() == ShareMutationOutcome.DEFINITE_REJECTED) {
                            ShareMutationResult restored = invokePlayerRestoration(journal, record, playerId, companyId,
                                    playerRemoval, quantity);

                            if (restored != null && restored.getOutcome() == ShareMutationOutcome.CONFIRMED) {
                                if (journal.saveCompensated(record)) {
                                    return result(SellTransactionResult.Status.COMPENSATED, companyId, quantity, record);
                                }
                            }
                            return reconciliationResult(journal, record, companyId, quantity, "Player restoration outcome is ambiguous");
                        }
                        return reconciliationResult(journal, record, companyId, quantity, "Company add outcome is ambiguous");
                    }

                    // 30 ECONOMY_CREDIT
                    EconomyMutationResult credit = invoke(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT,
                            30, total, quantity, () -> economy.deposit(economyActor, total));

                    if (credit == null) {
                        return reconciliationResult(journal, record, companyId, quantity, "Economy credit request or outcome persistence is unresolved");
                    }

                    if (credit.outcome() != EconomyMutationOutcome.CONFIRMED) {
                        if (credit.outcome() == EconomyMutationOutcome.AMBIGUOUS) {
                            return reconciliationResult(journal, record, companyId, quantity, "Vault deposit outcome is ambiguous");
                        }
                        // Economy DEFINITE_REJECTED => begin share compensation

                        // 40 COMPANY_SHARE_REMOVAL
                        ShareMutationResult companyRemoval = invokeCompanyRemovalComp(journal, record, companyId, companyAdd, quantity);
                        if (companyRemoval != null && companyRemoval.getOutcome() == ShareMutationOutcome.CONFIRMED) {

                            // 50 PLAYER_SHARE_RESTORATION
                            ShareMutationResult playerRestored = invokePlayerRestoration(journal, record, playerId, companyId,
                                    playerRemoval, quantity);

                            if (playerRestored != null && playerRestored.getOutcome() == ShareMutationOutcome.CONFIRMED) {
                                if (journal.saveCompensated(record)) {
                                    return result(SellTransactionResult.Status.COMPENSATED, companyId, quantity, record);
                                }
                            }
                        }
                        return reconciliationResult(journal, record, companyId, quantity, "Compensation failure after economy rejection");
                    }

                    // 70 MARKET_IMPACT
                    if (!request(journal, record, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0, quantity)) {
                        reconcile(journal, record, "Market impact request could not be persisted");
                        return result(SellTransactionResult.Status.MARKET_IMPACT_FAILURE_AFTER_COMMIT, companyId, quantity, record);
                    }

                    boolean impact;
                    try {
                        impact = plugin.getCompaniesService().applyMarketImpact(playerId, companyId, quantity, false); // isPurchase=false
                    } catch (RuntimeException e) {
                        impact = false;
                    }

                    if (!recordOutcome(journal, record, MarketTransactionEffectType.MARKET_IMPACT,
                            impact ? MarketTransactionEffectState.CONFIRMED : MarketTransactionEffectState.AMBIGUOUS,
                            impact ? "confirmed" : "market impact outcome is ambiguous")) {
                        reconcile(journal, record, "Market impact outcome persistence failed");
                        return result(SellTransactionResult.Status.MARKET_IMPACT_FAILURE_AFTER_COMMIT, companyId, quantity, record);
                    }

                    if (!impact) {
                        reconcile(journal, record, "Market impact outcome is ambiguous");
                        return result(SellTransactionResult.Status.MARKET_IMPACT_FAILURE_AFTER_COMMIT, companyId, quantity, record);
                    }

                    if (!journal.complete(record)) {
                        return reconciliationResult(journal, record, companyId, quantity, "Transaction completion persistence failed");
                    }

                    // The fee kept from the sale goes to the server treasury (if configured).
                    TreasuryService.credit(plugin.getTreasuryService(), gross - total);
                    return result(SellTransactionResult.Status.SUCCESS, companyId, quantity, record);
                    }
                }
            }
        }
    }

    private ShareMutationResult invokePlayerRemoval(MarketTransactionJournal journal, MarketTransactionRecord record, UUID p, long id, int q) {
        if (!request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0, q)) return null;
        ShareMutationResult result;
        try {
            result = plugin.getPlayersService().removeSharesFromPlayerTyped(p, id, q);
        } catch (RuntimeException e) {
            result = ShareMutationResult.ambiguous(ShareMutationOperation.PLAYER_REMOVE,
                    p, id, q, null, null, null, null, null, e.getClass().getSimpleName());
        }
        if (!recordOutcome(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, state(result), result.getDiagnostic())) {
            reconcile(journal, record, "Player removal outcome persistence failed");
            return null;
        }
        return result;
    }

    private ShareMutationResult invokeCompanyAdd(MarketTransactionJournal journal, MarketTransactionRecord record, long id, int q) {
        if (!request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0, q)) return null;
        ShareMutationResult result;
        try {
            result = plugin.getCompaniesService().addSharesToCompanyTyped(id, q);
        } catch (RuntimeException e) {
            result = ShareMutationResult.ambiguous(ShareMutationOperation.COMPANY_ADD,
                    null, id, q, null, null, null, null, null, e.getClass().getSimpleName());
        }
        if (!recordOutcome(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, state(result), result.getDiagnostic())) {
            reconcile(journal, record, "Company add outcome persistence failed");
            return null;
        }
        return result;
    }

    private ShareMutationResult invokePlayerRestoration(MarketTransactionJournal journal, MarketTransactionRecord record,
                                                        UUID p, long id, ShareMutationResult removed, int quantity) {
        if (!request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, 50, 0.0, quantity)) return null;
        ShareMutationResult result;
        try {
            result = plugin.getPlayersService().restoreRemovedPlayerSharesTyped(removed.playerRemovalEvidence());
        } catch (RuntimeException e) {
            result = ShareMutationResult.ambiguous(ShareMutationOperation.PLAYER_REMOVE_RESTORE,
                    p, id, quantity, null, null, null, null, null, e.getClass().getSimpleName());
        }
        if (!recordOutcome(journal, record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, state(result), result.getDiagnostic())) {
            reconcile(journal, record, "Player restoration outcome persistence failed");
            return null;
        }
        return result;
    }

    private ShareMutationResult invokeCompanyRemovalComp(MarketTransactionJournal journal, MarketTransactionRecord record,
                                                         long id, ShareMutationResult added, int quantity) {
        if (!request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 40, 0.0, quantity)) return null;
        ShareMutationResult result;
        try {
            result = plugin.getCompaniesService().removeConfirmedCompanyAddTyped(id, added.companyAddEvidence());
        } catch (RuntimeException e) {
            result = ShareMutationResult.ambiguous(ShareMutationOperation.COMPANY_ADD_RESTORE,
                    null, id, quantity, null, null, null, null, null, e.getClass().getSimpleName());
        }
        if (!recordOutcome(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, state(result), result.getDiagnostic())) {
            reconcile(journal, record, "Company removal compensation outcome persistence failed");
            return null;
        }
        return result;
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

    private SellTransactionResult result(SellTransactionResult.Status status, long companyId, int quantity,
                                         MarketTransactionRecord record) {
        return SellTransactionResult.of(status, companyId, quantity,
                record == null ? null : record.getTransactionId(),
                record == null ? Double.NaN : record.getValue());
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

    private SellTransactionResult reconciliationResult(MarketTransactionJournal journal,
                                                      MarketTransactionRecord record,
                                                      long companyId, int quantity,
                                                      String reason) {
        if (journal.markReconciliationRequired(record, reason)) {
            return result(SellTransactionResult.Status.RECONCILIATION_REQUIRED,
                    companyId, quantity, record);
        }
        return result(SellTransactionResult.Status.RECONCILIATION_REQUIRED,
                companyId, quantity, record);
    }

    private void reconcile(MarketTransactionJournal j, MarketTransactionRecord r, String reason) {
        j.markReconciliationRequired(r, reason);
    }
}