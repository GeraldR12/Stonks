package me.geraldr12.data.services;

public record BuyTransactionResult(
        Status status,
        long companyId,
        int quantity,
        String transactionId,
        double totalValue) {

    public enum Status {
        SUCCESS,
        VALIDATION_REJECTED,
        INSUFFICIENT_FUNDS,
        INSUFFICIENT_SHARES,
        PAYMENT_REJECTED,
        COMPENSATED,
        COMMITTED_MARKET_RECONCILIATION,
        BLOCKED_PENDING_REVIEW,
        RECONCILIATION_REQUIRED,
        CRITICAL_UNRESOLVED
    }

    public BuyTransactionResult {
        if (status == null) throw new IllegalArgumentException("Status is required");
        if (status != Status.VALIDATION_REJECTED && companyId < 0) {
            throw new IllegalArgumentException("Company ID must not be negative");
        }
        if (status != Status.VALIDATION_REJECTED && quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }

    public BuyTransactionResult(Status status, long companyId, int quantity, String transactionId) {
        this(status, companyId, quantity, transactionId, Double.NaN);
    }

    public static BuyTransactionResult of(Status status, long companyId, int quantity,
                                          String transactionId) {
        return new BuyTransactionResult(status, companyId, quantity, transactionId);
    }

    public static BuyTransactionResult of(Status status, long companyId, int quantity,
                                          String transactionId, double totalValue) {
        return new BuyTransactionResult(status, companyId, quantity, transactionId, totalValue);
    }
}
