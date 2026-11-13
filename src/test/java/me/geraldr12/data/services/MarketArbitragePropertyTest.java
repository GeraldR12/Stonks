package me.geraldr12.data.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketArbitragePropertyTest {
    @Test
    void attackABulkBuyThenBulkSellLosesMoney() {
        Simulation market = new Simulation(76.0);
        market.buy(50);
        market.sell(50);

        assertEquals(0, market.shares);
        assertTrue(market.balance < 0, "Bulk round trip must lose money: " + market.balance);
        System.out.println("Attack A balance delta: " + market.balance);
    }

    @Test
    void attackBSplitBuysThenBulkSellLosesMoney() {
        Simulation market = new Simulation(76.0);
        for (int i = 0; i < 50; i++) {
            market.buy(1);
        }
        market.sell(50);

        assertEquals(0, market.shares);
        assertTrue(market.balance < 0, "Split round trip must lose money: " + market.balance);
        System.out.println("Attack B balance delta: " + market.balance);
    }

    @Test
    void twoThousandSeededClosedTradingSequencesNeverCreateMoney() {
        Random random = new Random(170017L);
        double largestDelta = Double.NEGATIVE_INFINITY;
        for (int sequence = 0; sequence < 2000; sequence++) {
            Simulation market = new Simulation(2.0 + random.nextDouble() * 1000.0);
            int trades = 10 + random.nextInt(191);
            for (int trade = 0; trade < trades; trade++) {
                if (market.shares == 0 || (market.shares < 1000 && random.nextBoolean())) {
                    market.buy(1 + random.nextInt(1000 - market.shares));
                } else {
                    market.sell(1 + random.nextInt(market.shares));
                }
            }
            if (market.shares > 0) {
                market.sell(market.shares);
            }

            assertEquals(0, market.shares);
            assertTrue(Double.isFinite(market.balance));
            assertTrue(market.balance <= 0, "Sequence " + sequence + " created " + market.balance);
            largestDelta = Math.max(largestDelta, market.balance);
        }
        System.out.println("Closed random sequences: 2000; largest balance delta: " + largestDelta);
    }

    private static final class Simulation {
        private static final double K = 0.00003;
        private double price;
        private double balance;
        private int shares;

        private Simulation(double price) {
            this.price = price;
        }

        private void buy(int quantity) {
            double gross = MarketPricing.buyGross(price, quantity, K);
            double total = roundDown(gross + MarketPricing.fee(gross, 0.01, 1.0));
            assertTrue(Double.isFinite(total) && total > 0);
            balance -= total;
            price = MarketPricing.buyPriceAfter(price, quantity, K);
            shares += quantity;
        }

        private void sell(int quantity) {
            double gross = MarketPricing.sellGross(price, quantity, K);
            double total = roundDown(gross - MarketPricing.fee(gross, 0.01, 1.0));
            assertTrue(Double.isFinite(total) && total > 0);
            balance += total;
            price = MarketPricing.sellPriceAfter(price, quantity, K);
            shares -= quantity;
        }

        private static double roundDown(double amount) {
            return BigDecimal.valueOf(amount).setScale(0, RoundingMode.DOWN).doubleValue();
        }
    }
}
