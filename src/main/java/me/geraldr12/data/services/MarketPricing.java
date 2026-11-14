package me.geraldr12.data.services;

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
}
