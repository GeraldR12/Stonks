package me.geraldr12.utils.random;

import lombok.Getter;

import java.util.concurrent.ThreadLocalRandom;

@Getter
public class StocksRandomizer {

    private final int risk;
    private final double initialStockValue;
    private final double minLimit;
    private final double maxLimit;

    public StocksRandomizer(int risk, double initialStockValue) {
        this.risk = risk;
        this.initialStockValue = initialStockValue;
        this.minLimit = initialStockValue * (0.6 / risk);
        this.maxLimit = initialStockValue * (3 * risk);
    }

    public double getRandomStockValue(double currentStockValue, double quote) {

        double newStockValue = currentStockValue;
        newStockValue += newStockValue * quote;
        return newStockValue;

    }

    public double getRandomQuote(double currentStockValue) {

        boolean randomSignal = getRandomSignal();
        double randomQuotePercentage = getRandomQuoteAsPercentage();
        double generatedNoise = generateNoise();

        return boundedQuote(currentStockValue, randomQuotePercentage + generatedNoise,
                randomSignal, this.minLimit, this.maxLimit);

    }

    static double boundedQuote(double current, double magnitude, boolean positive,
                               double minLimit, double maxLimit) {
        double stockChange = current * magnitude;
        if (positive) {
            return current + stockChange > maxLimit ? -magnitude : magnitude;
        }
        if (current - stockChange > minLimit) {
            return -magnitude;
        }
        return current > minLimit ? (minLimit - current) / current : 0;
    }

    private double generateNoise() {

        ThreadLocalRandom r = ThreadLocalRandom.current();
        double randomPercentage = r.nextDouble(0d, 1d);

        // There is some probability that the stock value will change drastically (10% - 30%)
        // This probability will increase with the risk of the company
        if (randomPercentage < 0.01 * (this.risk * 5)) {
            return r.nextDouble(0.1, 0.3);
        }

        return 0;

    }

    private double getRandomQuoteAsPercentage() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return r.nextDouble(0d, 1.5 * this.risk) / 100d;
    }

    // True = positive signal
    // False = negative signal
    private boolean getRandomSignal() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return r.nextBoolean();
    }

    /**
     * Checks if the stock value is in the danger zone and if it can crash.
     *
     * @param stockValue the current stock value
     * @return true if the stock should crash, false otherwise
     */
    public boolean canCrash(double stockValue) {
        double dangerZoneThreshold = this.minLimit + (this.initialStockValue - this.minLimit) * 0.01;
        return stockValue < dangerZoneThreshold;
    }

}
