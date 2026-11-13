package me.geraldr12.data.services;

import java.util.UUID;

final class ConfirmedPlayerRemovalEvidence {
    private final UUID playerId;
    private final long companyId;
    private final long beforeQuantity;
    private final long afterQuantity;
    private final Double beforeAveragePrice;

    ConfirmedPlayerRemovalEvidence(UUID playerId, long companyId, long beforeQuantity, long afterQuantity, Double beforeAveragePrice) {
        if (playerId == null) throw new IllegalArgumentException("Player ID is required");
        if (beforeQuantity <= afterQuantity) throw new IllegalArgumentException("Invalid player removal quantity change");
        if (afterQuantity < 0) throw new IllegalArgumentException("After quantity cannot be negative");
        if (beforeAveragePrice == null || !Double.isFinite(beforeAveragePrice) || beforeAveragePrice <= 0) {
            throw new IllegalArgumentException("Invalid average price");
        }
        this.playerId = playerId;
        this.companyId = companyId;
        this.beforeQuantity = beforeQuantity;
        this.afterQuantity = afterQuantity;
        this.beforeAveragePrice = beforeAveragePrice;
    }

    UUID playerId() { return playerId; }
    long companyId() { return companyId; }
    long beforeQuantity() { return beforeQuantity; }
    long afterQuantity() { return afterQuantity; }
    Double beforeAveragePrice() { return beforeAveragePrice; }
}
