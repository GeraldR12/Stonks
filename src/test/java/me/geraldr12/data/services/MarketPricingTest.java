package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MarketPricingTest {
    @Test
    void exponentialPricesAndIntegratedGrossMatchFormulas() {
        double p = 76.0;
        int n = 1000;
        double k = 0.00003;

        assertEquals(p * Math.exp(k * n), MarketPricing.buyPriceAfter(p, n, k), 1e-12);
        assertEquals(p * Math.exp(-k * n), MarketPricing.sellPriceAfter(p, n, k), 1e-12);
        assertEquals(p * Math.expm1(k * n) / k, MarketPricing.buyGross(p, n, k), 1e-9);
        assertEquals(p * -Math.expm1(-k * n) / k, MarketPricing.sellGross(p, n, k), 1e-9);
        assertEquals(0.01, MarketPricing.sellPriceAfter(0.02, 1000, 0.01));
    }

    @Test
    void splittingPurchasesPreservesFinalPriceAndGross() {
        double p = 76.0;
        double price = p;
        double gross = 0.0;
        double k = 0.00003;
        for (int i = 0; i < 1000; i++) {
            gross += MarketPricing.buyGross(price, 1, k);
            price = MarketPricing.buyPriceAfter(price, 1, k);
        }

        assertEquals(MarketPricing.buyPriceAfter(p, 1000, k), price, p * 1e-9);
        assertEquals(MarketPricing.buyGross(p, 1000, k), gross, gross * 1e-9);
        assertEquals(MarketPricing.buyPriceAfter(p, 1000, k),
                MarketPricing.buyPriceAfter(MarketPricing.buyPriceAfter(p, 137, k), 863, k), p * 1e-9);
    }

    @Test
    void untaxedRoundTripsRecoverGrossAndStartingPrice() {
        for (double p : new double[]{0.01, 1.0, 76.0, 10000.0}) {
            for (double k : new double[]{0.0, 1e-14, 0.00003, 0.01}) {
                for (int n : new int[]{1, 50, 1000}) {
                    double boughtPrice = MarketPricing.buyPriceAfter(p, n, k);
                    double cost = MarketPricing.buyGross(p, n, k);
                    assertEquals(cost, MarketPricing.sellGross(boughtPrice, n, k), cost * 1e-9);
                    assertEquals(p, MarketPricing.sellPriceAfter(boughtPrice, n, k), p * 1e-9);
                }
            }
        }
    }

    @Test
    void zeroImpactPreservesPriceAndMultipliesQuantity() {
        assertEquals(12.75, MarketPricing.buyPriceAfter(12.75, 1000, 0));
        assertEquals(12.75, MarketPricing.sellPriceAfter(12.75, 1000, 0));
        assertEquals(12750.0, MarketPricing.buyGross(12.75, 1000, 0));
        assertEquals(12750.0, MarketPricing.sellGross(12.75, 1000, 0));
    }

    @Test
    void missingMarketSettingsUseSafeDefaults() {
        Stonks plugin = mock(Stonks.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(call -> call.getArgument(1));
        CompaniesService companies = new CompaniesService(plugin, mock(CompaniesRepository.class));

        assertEquals(0.00003, companies.getMarketImpactPerShare());
        assertEquals(0.01, companies.getTradingTax());
        assertEquals(1.0, companies.getTradingMinimum());
    }

    @Test
    void invalidSettingsFallBackAndWarnOncePerKeyWhileBoundariesAreAccepted() {
        Stonks plugin = mock(Stonks.class);
        FileConfiguration config = mock(FileConfiguration.class);
        Logger logger = mock(Logger.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        CompaniesService companies = new CompaniesService(plugin, mock(CompaniesRepository.class));
        for (double invalid : new double[]{-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            when(config.getDouble("Stonks.MarketImpactPerShare", 0.00003)).thenReturn(invalid);
            when(config.getDouble("Stonks.Taxes.Trading", 0.01)).thenReturn(invalid);
            when(config.getDouble("Stonks.Taxes.TradingMinimum", 1.0)).thenReturn(invalid);
            assertEquals(0.00003, companies.getMarketImpactPerShare());
            assertEquals(0.01, companies.getTradingTax());
            assertEquals(1.0, companies.getTradingMinimum());
        }
        when(config.getDouble("Stonks.MarketImpactPerShare", 0.00003)).thenReturn(0.010001);
        when(config.getDouble("Stonks.Taxes.Trading", 0.01)).thenReturn(0.5);
        assertEquals(0.00003, companies.getMarketImpactPerShare());
        assertEquals(0.01, companies.getTradingTax());
        verify(logger, times(3)).warning(anyString());

        when(config.getDouble("Stonks.MarketImpactPerShare", 0.00003)).thenReturn(0.01, 0.0);
        when(config.getDouble("Stonks.Taxes.Trading", 0.01)).thenReturn(Math.nextDown(0.5), 0.0);
        when(config.getDouble("Stonks.Taxes.TradingMinimum", 1.0)).thenReturn(Double.MAX_VALUE, 0.0);
        assertEquals(0.01, companies.getMarketImpactPerShare());
        assertEquals(Math.nextDown(0.5), companies.getTradingTax());
        assertEquals(Double.MAX_VALUE, companies.getTradingMinimum());
        assertEquals(0.0, companies.getMarketImpactPerShare());
        assertEquals(0.0, companies.getTradingTax());
        assertEquals(0.0, companies.getTradingMinimum());
        verifyNoMoreInteractions(logger);
    }

    @Test
    void feeUsesRateOrMinimumWhicheverIsGreater() {
        assertEquals(1.0, MarketPricing.fee(50.0, 0.01, 1.0));
        assertEquals(10.0, MarketPricing.fee(1000.0, 0.01, 1.0));
        assertEquals(1.0, MarketPricing.fee(100.0, 0.01, 1.0));
        assertEquals(0.0, MarketPricing.fee(100.0, 0.0, 0.0));
    }
}
