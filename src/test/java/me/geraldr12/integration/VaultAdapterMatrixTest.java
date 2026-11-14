package me.geraldr12.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class VaultAdapterMatrixTest {
    @Test
    void statusBranchesRemainExplicit() {
        assertEquals(IntegrationStatus.PLUGIN_MISSING, VaultAdapter.resolveStatus(null, null));
        assertEquals(IntegrationStatus.DATA_UNAVAILABLE, VaultAdapter.resolveStatus(new Object(), null));
        assertEquals(IntegrationStatus.AVAILABLE, VaultAdapter.resolveStatus(new Object(), nullSafeEconomy()));
    }

    @Test
    void zeroAndPositiveBalancesAreAccepted() {
        assertEquals(0.0, VaultAdapter.validateBalance(0.0));
        assertEquals(12.5, VaultAdapter.validateBalance(12.5));
    }

    @Test
    void invalidBalancesAreRejected() {
        assertThrows(IllegalStateException.class, () -> VaultAdapter.validateBalance(-1.0));
        assertThrows(IllegalStateException.class, () -> VaultAdapter.validateBalance(Double.NaN));
        assertThrows(IllegalStateException.class, () -> VaultAdapter.validateBalance(Double.POSITIVE_INFINITY));
        assertThrows(IllegalStateException.class, () -> VaultAdapter.validateBalance(Double.NEGATIVE_INFINITY));
    }

    @Test
    void providerNameAndBalanceAreReadOnly() {
        net.milkbowl.vault.economy.Economy economy = org.mockito.Mockito.mock(
                net.milkbowl.vault.economy.Economy.class);
        when(economy.getName()).thenReturn("TestEconomy");
        org.bukkit.OfflinePlayer player = org.mockito.Mockito.mock(org.bukkit.OfflinePlayer.class);
        when(economy.getBalance(player)).thenReturn(8.0);
        VaultAdapter adapter = new VaultAdapter(economy, IntegrationStatus.AVAILABLE, "2.0", () -> true);
        assertEquals("TestEconomy", adapter.providerName());
        assertEquals(8.0, adapter.balance(player));
        verify(economy).getName();
        verify(economy).getBalance(player);
        verifyNoMoreInteractions(economy);
    }

    @Test
    void offMainThreadBalanceIsRejectedBeforeProviderAccess() {
        net.milkbowl.vault.economy.Economy economy = org.mockito.Mockito.mock(
                net.milkbowl.vault.economy.Economy.class);
        VaultAdapter adapter = new VaultAdapter(economy, IntegrationStatus.AVAILABLE, "2.0", () -> false);
        assertThrows(IllegalStateException.class, () -> adapter.balance(null));
        verifyNoMoreInteractions(economy);
    }

    private static net.milkbowl.vault.economy.Economy nullSafeEconomy() {
        return org.mockito.Mockito.mock(net.milkbowl.vault.economy.Economy.class);
    }
}
