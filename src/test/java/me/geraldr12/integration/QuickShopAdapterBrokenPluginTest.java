package me.geraldr12.integration;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class QuickShopAdapterBrokenPluginTest {

    @Mock
    Plugin plugin;

    @Test
    void brokenPlugin_illegalStateException_returnsApiUnsupported() {
        lenient().when(plugin.getDescription()).thenReturn(new org.bukkit.plugin.PluginDescriptionFile(
                "QuickShop-Hikari", "1.0.0", "me.geraldr12.Stonks"));
        lenient().when(plugin.getName()).thenReturn("QuickShop-Hikari");

        // Validator que lança IllegalStateException (simula plugin quebrado ao carregar)
        QuickShopAdapter.ApiValidator brokenValidator = p -> {
            throw new IllegalStateException("The plugin classloader for QuickShop-Hikari has thrown a zip file error");
        };

        IntegrationStatus status = QuickShopAdapter.resolveStatus(plugin, brokenValidator);

        assertEquals(IntegrationStatus.API_UNSUPPORTED, status);
    }

    @Test
    void brokenPlugin_runtimeException_returnsApiUnsupported() {
        lenient().when(plugin.getDescription()).thenReturn(new org.bukkit.plugin.PluginDescriptionFile(
                "QuickShop-Hikari", "1.0.0", "me.geraldr12.Stonks"));
        lenient().when(plugin.getName()).thenReturn("QuickShop-Hikari");

        // Validator que lança RuntimeException genérica
        QuickShopAdapter.ApiValidator brokenValidator = p -> {
            throw new RuntimeException("Unexpected error");
        };

        IntegrationStatus status = QuickShopAdapter.resolveStatus(plugin, brokenValidator);

        assertEquals(IntegrationStatus.API_UNSUPPORTED, status);
    }
}