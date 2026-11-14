package me.geraldr12.data.services;

import java.util.UUID;

public record SellQuote(long companyId, UUID playerUuid, int quantity,
                        double canonicalUnitSharePrice, double canonicalTotalValue, long quoteTimestamp) {
    public SellQuote {
        if (companyId < 0 || playerUuid == null || quantity <= 0
                || !Double.isFinite(canonicalUnitSharePrice) || canonicalUnitSharePrice <= 0
                || !Double.isFinite(canonicalTotalValue) || canonicalTotalValue <= 0 || quoteTimestamp <= 0) {
            throw new IllegalArgumentException("Invalid sell quote");
        }
        double expected;
        try {
            expected = Math.multiplyExact(quantity, 1L) * canonicalUnitSharePrice;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid sell quote", e);
        }
        if (!Double.isFinite(expected) || Double.compare(expected, canonicalTotalValue) != 0) {
            throw new IllegalArgumentException("Sell quote total does not match unit price");
        }
    }
}
