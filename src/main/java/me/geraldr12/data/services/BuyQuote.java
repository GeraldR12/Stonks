package me.geraldr12.data.services;

import java.util.UUID;

public record BuyQuote(long companyId, UUID playerUuid, int quantity,
                       double unitSharePrice, double totalValue, long quoteTimestamp) {
    public BuyQuote {
        if (companyId < 0 || playerUuid == null || quantity <= 0
                || !Double.isFinite(unitSharePrice) || unitSharePrice <= 0
                || !Double.isFinite(totalValue) || totalValue <= 0 || quoteTimestamp <= 0) {
            throw new IllegalArgumentException("Invalid buy quote");
        }
        double expected;
        try {
            expected = Math.multiplyExact(quantity, 1L) * unitSharePrice;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid buy quote", e);
        }
        if (!Double.isFinite(expected) || Double.compare(expected, totalValue) != 0) {
            throw new IllegalArgumentException("Buy quote total does not match unit price");
        }
    }
}
