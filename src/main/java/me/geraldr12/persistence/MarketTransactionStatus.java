package me.geraldr12.persistence;

public enum MarketTransactionStatus {
    PREPARED,
    IN_PROGRESS,
    COMMITTED,
    COMPENSATING,
    COMPLETED,
    COMPENSATED,
    ABORTED,
    RECONCILIATION_REQUIRED,

    @Deprecated
    ECONOMY_APPLIED,
    @Deprecated
    STATE_COMMITTED,
    @Deprecated
    MARKET_IMPACT_STARTED,
    @Deprecated
    MARKET_IMPACT_APPLIED;

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == ABORTED;
    }

    public boolean isBlocking() {
        return !isTerminal();
    }
}
