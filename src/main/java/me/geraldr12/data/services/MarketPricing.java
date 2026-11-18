package me.geraldr12.data.services;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MarketPricing {
    private MarketPricing() {
    }

    public static double buyPriceAfter(double p, int n, double k) {
        return p * Math.exp(k * n);
    }

    public static double sellPriceAfter(double p, int n, double k) {
        return Math.max(0.01, p * Math.exp(-k * n));
    }

    /** Returns the integrated purchase cost along the price curve, before fees and rounding. */
    public static double buyGross(double p, int n, double k) {
        return k == 0 ? p * n : p * Math.expm1(k * n) / k;
    }

    /** Returns the integrated sale proceeds along the price curve, before fees and rounding. */
    public static double sellGross(double p, int n, double k) {
        return k == 0 ? p * n : p * -Math.expm1(-k * n) / k;
    }

    public static double fee(double gross, double rate, double minimum) {
        return Math.max(gross * rate, minimum);
    }

    public record Quote(double gross, double fee, double total, double priceAfter) {}

    public static Quote quote(double price, int quantity, boolean buy, int digits, CompaniesService companies) {
        if (!Double.isFinite(price) || price <= 0 || quantity <= 0 || digits < -1) return null;
        double impact = companies.getMarketImpactPerShare();
        double gross = buy ? buyGross(price, quantity, impact) : sellGross(price, quantity, impact);
        double fee = fee(gross, companies.getTradingTax(), companies.getTradingMinimum());
        double total = buy ? gross + fee : gross - fee;
        double after = buy ? buyPriceAfter(price, quantity, impact) : sellPriceAfter(price, quantity, impact);
        if (!Double.isFinite(total) || !Double.isFinite(after) || after <= 0) return null;
        double settlement = digits < 0 ? total
                : BigDecimal.valueOf(total).setScale(digits, RoundingMode.DOWN).doubleValue();
        return new Quote(gross, fee, settlement, after);
    }
}
