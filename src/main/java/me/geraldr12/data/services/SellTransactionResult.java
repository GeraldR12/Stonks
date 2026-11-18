package me.geraldr12.data.services;

public record SellTransactionResult(
        Status status,
        long companyId,
        int quantity,
        String transactionId,
        double totalValue) {

    public enum Status {
        SUCCESS,
        QUOTE_CHANGED,
        VALIDATION_REJECTED,
        INSUFFICIENT_SHARES,
        FEE_EXCEEDS_VALUE,
        /** The company's funds cannot cover the buyback; totalValue carries the funds available. */
        INSUFFICIENT_COMPANY_FUNDS,
        DEFINITE_FAILURE,
        COMPENSATED,
        BLOCKED_PENDING_REVIEW,
        RECONCILIATION_REQUIRED,
        MARKET_IMPACT_FAILURE_AFTER_COMMIT
    }

    public SellTransactionResult {
        if (status == null) throw new IllegalArgumentException("Status is required");
        if (companyId < 0) throw new IllegalArgumentException("Company ID must not be negative");
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
    }

    public SellTransactionResult(Status status, long companyId, int quantity, String transactionId) {
        this(status, companyId, quantity, transactionId, Double.NaN);
    }

    public static SellTransactionResult of(Status status, long companyId, int quantity, String transactionId) {
        return new SellTransactionResult(status, companyId, quantity, transactionId);
    }

    public static SellTransactionResult of(Status status, long companyId, int quantity, String transactionId,
                                           double totalValue) {
        return new SellTransactionResult(status, companyId, quantity, transactionId, totalValue);
    }
}
