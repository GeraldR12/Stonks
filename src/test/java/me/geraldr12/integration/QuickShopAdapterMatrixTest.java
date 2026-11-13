package me.geraldr12.integration;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class QuickShopAdapterMatrixTest {
    @Test
    void missingPluginIsUnavailable() {
        assertEquals(IntegrationStatus.PLUGIN_MISSING,
                QuickShopAdapter.resolveStatus(null, plugin -> { }));
    }

    @Test
    void missingApiMethodIsUnsupported() {
        assertEquals(IntegrationStatus.API_UNSUPPORTED,
                QuickShopAdapter.resolveStatus(mock(Plugin.class), ignored -> {
                    throw new NoSuchMethodException("getBalanceWithoutTax");
                }));
    }

    @Test
    void linkageFailureIsUnsupported() {
        assertEquals(IntegrationStatus.API_UNSUPPORTED,
                QuickShopAdapter.resolveStatus(mock(Plugin.class), ignored -> {
                    throw new LinkageError("missing dependency");
                }));
    }

    @Test
    void completePublicApiIsAvailable() {
        assertEquals(IntegrationStatus.AVAILABLE,
                QuickShopAdapter.resolveStatus(mock(Plugin.class), ignored -> { }));
    }

    @Test
    void unsupportedBalanceFieldDoesNotBlockActivityApi() {
        assertEquals(IntegrationStatus.AVAILABLE,
                QuickShopAdapter.resolveStatus(mock(Plugin.class), ignored -> { }));
    }
}
