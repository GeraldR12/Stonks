package me.geraldr12.utils.random;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StocksRandomizerFloorTest {
    @Test
    void declineCrossingFloorStopsAtFloor() {
        StocksRandomizer randomizer = new StocksRandomizer(1, 100.0);
        double current = 61.0;

        double quote = StocksRandomizer.boundedQuote(current, 0.3, false,
                randomizer.getMinLimit(), randomizer.getMaxLimit());

        assertTrue(quote <= 0);
        assertEquals(randomizer.getMinLimit(), randomizer.getRandomStockValue(current, quote), 1e-9);
    }

    @Test
    void declineAtFloorReturnsZero() {
        assertEquals(0.0, StocksRandomizer.boundedQuote(60.0, 0.3, false, 60.0, 300.0));
    }

    @Test
    void declineReachingFloorExactlyStaysNegative() {
        double quote = StocksRandomizer.boundedQuote(75.0, 0.2, false, 60.0, 300.0);

        assertEquals(-0.2, quote);
        assertEquals(60.0, new StocksRandomizer(1, 100.0).getRandomStockValue(75.0, quote), 1e-9);
    }

    @Test
    void declineBelowFloorDoesNotBecomePositive() {
        assertEquals(0.0, StocksRandomizer.boundedQuote(59.0, 0.3, false, 60.0, 300.0));
    }

    @Test
    void riseCrossingCeilingStillReturnsNegativeMagnitude() {
        assertEquals(-0.1, StocksRandomizer.boundedQuote(290.0, 0.1, true, 60.0, 300.0));
    }

    @Test
    void riseReachingCeilingExactlyStaysPositive() {
        assertEquals(0.2, StocksRandomizer.boundedQuote(250.0, 0.2, true, 60.0, 300.0));
    }

    @Test
    void changesWithinLimitsPreserveSign() {
        assertEquals(0.1, StocksRandomizer.boundedQuote(100.0, 0.1, true, 60.0, 300.0));
        assertEquals(-0.1, StocksRandomizer.boundedQuote(100.0, 0.1, false, 60.0, 300.0));
    }

    @Test
    void tenThousandSeededDeclinesNeverRiseOrCrossFloor() {
        Random random = new Random(210021L);
        for (int i = 0; i < 10000; i++) {
            int risk = 1 + random.nextInt(5);
            StocksRandomizer randomizer = new StocksRandomizer(risk, 100.0);
            double minLimit = randomizer.getMinLimit();
            double current = minLimit * (1.0 + random.nextDouble() * 0.5);
            double magnitude = random.nextDouble() * (1.5 * risk) / 100.0;
            if (random.nextDouble() < 0.01 * (risk * 5)) {
                magnitude += 0.1 + random.nextDouble() * 0.2;
            }

            double quote = StocksRandomizer.boundedQuote(current, magnitude, false,
                    minLimit, randomizer.getMaxLimit());
            double price = randomizer.getRandomStockValue(current, quote);

            assertTrue(quote <= 0, "Positive decline at sample " + i);
            assertTrue(price <= current, "Price rose at sample " + i);
            assertTrue(price >= minLimit, "Price below floor at sample " + i);
        }
    }

    @Test
    void tenThousandRandomQuotesNearFloorNeverCrossIt() {
        for (int i = 0; i < 10000; i++) {
            StocksRandomizer randomizer = new StocksRandomizer(1 + i % 5, 100.0);
            double current = randomizer.getMinLimit() * 1.001;
            double quote = randomizer.getRandomQuote(current);
            double price = randomizer.getRandomStockValue(current, quote);

            assertTrue(price >= randomizer.getMinLimit(), "Price below floor at sample " + i);
        }
    }
}
