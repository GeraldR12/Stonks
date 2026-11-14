package me.geraldr12.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class VaultEconomyMutationGatewayTest {

    @Test
    void confirmsExactWithdrawAndDoesNotDeposit() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(economy.getName()).thenReturn("TestEconomy");
        when(economy.withdrawPlayer(player, 12.5))
                .thenReturn(response(12.5, EconomyResponse.ResponseType.SUCCESS, null));

        EconomyMutationResult result = new VaultEconomyMutationGateway(economy, () -> true).withdraw(player, 12.5);

        assertEquals(EconomyMutationOutcome.CONFIRMED, result.outcome());
        assertEquals("TestEconomy", result.providerName());
        verify(economy).withdrawPlayer(player, 12.5);
        verify(economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void confirmsExactDepositAndDoesNotWithdraw() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(economy.depositPlayer(player, 12.5))
                .thenReturn(response(12.5, EconomyResponse.ResponseType.SUCCESS, null));

        EconomyMutationResult result = new VaultEconomyMutationGateway(economy, () -> true).deposit(player, 12.5);

        assertEquals(EconomyMutationOutcome.CONFIRMED, result.outcome());
        verify(economy).depositPlayer(player, 12.5);
        verify(economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void failureResponseWithUnchangedBalanceIsDefinitelyRejected() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(economy.withdrawPlayer(player, 3.0))
                .thenReturn(response(0.0, EconomyResponse.ResponseType.FAILURE, "insufficient funds"));
        when(economy.depositPlayer(player, 4.0))
                .thenReturn(response(0.0, EconomyResponse.ResponseType.FAILURE, "provider declined"));
        VaultEconomyMutationGateway gateway = new VaultEconomyMutationGateway(economy, () -> true);

        assertEquals(EconomyMutationOutcome.DEFINITE_REJECTED, gateway.withdraw(player, 3.0).outcome());
        assertEquals(EconomyMutationOutcome.DEFINITE_REJECTED, gateway.deposit(player, 4.0).outcome());
        verify(economy, times(1)).withdrawPlayer(player, 3.0);
        verify(economy, times(1)).depositPlayer(player, 4.0);
    }

    @Test
    void nullResponseAndExceptionAreAmbiguousWithoutRetry() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(economy.withdrawPlayer(player, 3.0)).thenReturn(null);
        VaultEconomyMutationGateway gateway = new VaultEconomyMutationGateway(economy, () -> true);

        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.withdraw(player, 3.0).outcome());
        when(economy.depositPlayer(player, 3.0)).thenThrow(new RuntimeException("provider failure"));
        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.deposit(player, 3.0).outcome());
        verify(economy, times(1)).withdrawPlayer(player, 3.0);
        verify(economy, times(1)).depositPlayer(player, 3.0);
    }

    @Test
    void successfulAmountMismatchOrNonFiniteAmountIsAmbiguous() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(economy.withdrawPlayer(player, 3.0))
                .thenReturn(response(2.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(economy.depositPlayer(player, 4.0))
                .thenReturn(response(Double.NaN, EconomyResponse.ResponseType.SUCCESS, null));
        VaultEconomyMutationGateway gateway = new VaultEconomyMutationGateway(economy, () -> true);

        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.withdraw(player, 3.0).outcome());
        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.deposit(player, 4.0).outcome());
    }

    @Test
    void invalidInputsFailBeforeProviderCall() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        VaultEconomyMutationGateway gateway = new VaultEconomyMutationGateway(economy, () -> true);

        for (double amount : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0.0, -1.0}) {
            assertThrows(IllegalArgumentException.class, () -> gateway.withdraw(player, amount));
            assertThrows(IllegalArgumentException.class, () -> gateway.deposit(player, amount));
        }
        assertThrows(IllegalArgumentException.class, () -> gateway.withdraw(null, 1.0));
        verifyNoInteractions(economy);
    }

    @Test
    void defaultGatewayRejectsWithoutInitializedBukkitServer() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        VaultEconomyMutationGateway gateway = new VaultEconomyMutationGateway(economy);

        assertThrows(IllegalStateException.class, () -> gateway.withdraw(player, 1.0));
        verifyNoInteractions(economy);
    }

    @Test
    void nullThreadCheckerFailsClosedBeforeProviderCall() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        VaultEconomyMutationGateway gateway = new VaultEconomyMutationGateway(economy, null);

        assertThrows(IllegalStateException.class, () -> gateway.withdraw(player, 1.0));
        assertThrows(IllegalStateException.class, () -> gateway.deposit(player, 1.0));
        verifyNoInteractions(economy);
    }

    @Test
    void offMainThreadFailsBeforeProviderCall() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        VaultEconomyMutationGateway gateway = new VaultEconomyMutationGateway(economy, () -> false);

        assertThrows(IllegalStateException.class, () -> gateway.withdraw(player, 1.0));
        assertThrows(IllegalStateException.class, () -> gateway.deposit(player, 1.0));
        verifyNoInteractions(economy);
    }

    @Test
    void diagnosticsAreBoundedAndSanitized() {
        Economy economy = mock(Economy.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        String diagnostic = "x\r\n\t".repeat(100);
        when(economy.withdrawPlayer(player, 1.0))
                .thenReturn(response(0.0, EconomyResponse.ResponseType.FAILURE, diagnostic));

        EconomyMutationResult result = new VaultEconomyMutationGateway(economy, () -> true).withdraw(player, 1.0);

        assertEquals(256, result.diagnostic().length());
        assertEquals(-1, result.diagnostic().indexOf('\n'));
        assertEquals(-1, result.diagnostic().indexOf('\r'));
        assertEquals(-1, result.diagnostic().indexOf('\t'));
    }

    private EconomyResponse response(double amount, EconomyResponse.ResponseType type, String message) {
        return new EconomyResponse(amount, 100.0, type, message);
    }
}
