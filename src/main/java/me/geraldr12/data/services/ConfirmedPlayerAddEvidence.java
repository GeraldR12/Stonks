package me.geraldr12.data.services;

import java.util.UUID;

final class ConfirmedPlayerAddEvidence {
    private final UUID playerId;
    private final long companyId;
    private final long beforeQuantity;
    private final long afterQuantity;
    private final Double beforeAveragePrice;
    private final Double afterAveragePrice;
    private final Double purchaseUnitPrice;

    ConfirmedPlayerAddEvidence(UUID playerId, long companyId, long beforeQuantity, long afterQuantity,
                               Double beforeAveragePrice, Double afterAveragePrice, Double purchaseUnitPrice) {
        this.playerId = playerId;
        this.companyId = companyId;
        this.beforeQuantity = beforeQuantity;
        this.afterQuantity = afterQuantity;
        this.beforeAveragePrice = beforeAveragePrice;
        this.afterAveragePrice = afterAveragePrice;
        this.purchaseUnitPrice = purchaseUnitPrice;
    }

    UUID playerId() { return playerId; }
    long companyId() { return companyId; }
    long beforeQuantity() { return beforeQuantity; }
    long afterQuantity() { return afterQuantity; }
    Double beforeAveragePrice() { return beforeAveragePrice; }
    Double afterAveragePrice() { return afterAveragePrice; }
    Double purchaseUnitPrice() { return purchaseUnitPrice; }
}
