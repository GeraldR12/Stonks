package me.geraldr12.data.services;

final class ConfirmedCompanyAddEvidence {
    private final long companyId;
    private final long requestedQuantity;
    private final long beforeQuantity;
    private final long afterQuantity;

    ConfirmedCompanyAddEvidence(long companyId, long requestedQuantity, long beforeQuantity, long afterQuantity) {
        if (requestedQuantity <= 0) throw new IllegalArgumentException("Requested quantity must be positive");
        if (beforeQuantity < 0) throw new IllegalArgumentException("Before quantity cannot be negative");
        if (afterQuantity != beforeQuantity + requestedQuantity) throw new IllegalArgumentException("Invalid company addition quantity change");

        this.companyId = companyId;
        this.requestedQuantity = requestedQuantity;
        this.beforeQuantity = beforeQuantity;
        this.afterQuantity = afterQuantity;
    }

    long companyId() { return companyId; }
    long requestedQuantity() { return requestedQuantity; }
    long beforeQuantity() { return beforeQuantity; }
    long afterQuantity() { return afterQuantity; }
}
