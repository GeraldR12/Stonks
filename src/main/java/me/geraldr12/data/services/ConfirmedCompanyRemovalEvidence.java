package me.geraldr12.data.services;

final class ConfirmedCompanyRemovalEvidence {
    private final long companyId;
    private final long requestedQuantity;
    private final long beforeQuantity;
    private final long afterQuantity;

    ConfirmedCompanyRemovalEvidence(long companyId, long requestedQuantity, long beforeQuantity, long afterQuantity) {
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
