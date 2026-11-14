package me.geraldr12.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class VaultEconomyMutationGatewayRejectionTest {
    private final Economy economy = mock(Economy.class);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private final VaultEconomyMutationGateway gateway =
            new VaultEconomyMutationGateway(economy, () -> true);

    @Test
    void failureWithUnchangedBalanceIsDefinitelyRejected() {
        when(economy.getBalance(player)).thenReturn(5.0);
        when(economy.withdrawPlayer(player, 25.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE));

        assertEquals(EconomyMutationOutcome.DEFINITE_REJECTED, gateway.withdraw(player, 25.0).outcome());
        var order = inOrder(economy);
        order.verify(economy).getBalance(player);
        order.verify(economy).withdrawPlayer(player, 25.0);
        order.verify(economy).getBalance(player);
        verify(economy, times(1)).withdrawPlayer(player, 25.0);
    }

    @Test
    void failedDepositWithUnchangedBalanceIsDefinitelyRejected() {
        when(economy.getBalance(player)).thenReturn(5.0);
        when(economy.depositPlayer(player, 25.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE));

        assertEquals(EconomyMutationOutcome.DEFINITE_REJECTED, gateway.deposit(player, 25.0).outcome());
    }

    @Test
    void failureWithChangedBalanceRemainsAmbiguous() {
        when(economy.getBalance(player)).thenReturn(100.0, 75.0);
        when(economy.withdrawPlayer(player, 25.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE));

        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.withdraw(player, 25.0).outcome());
    }

    @Test
    void notImplementedWithUnchangedBalanceRemainsAmbiguous() {
        when(economy.getBalance(player)).thenReturn(100.0);
        when(economy.withdrawPlayer(player, 25.0)).thenReturn(response(EconomyResponse.ResponseType.NOT_IMPLEMENTED));

        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.withdraw(player, 25.0).outcome());
    }

    @Test
    void nonFiniteBalancesRemainAmbiguous() {
        when(economy.withdrawPlayer(player, 25.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE));
        for (double balance : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            when(economy.getBalance(player)).thenReturn(balance);
            assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.withdraw(player, 25.0).outcome());
        }
        when(economy.getBalance(player)).thenReturn(100.0, Double.NaN);
        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.withdraw(player, 25.0).outcome());
    }

    @Test
    void initialBalanceExceptionIsAmbiguousWithoutMutation() {
        when(economy.getBalance(player)).thenThrow(new IllegalStateException("balance unavailable"));

        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.withdraw(player, 25.0).outcome());
        verify(economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void finalBalanceExceptionIsAmbiguousWithoutRetry() {
        when(economy.getBalance(player)).thenReturn(5.0).thenThrow(new IllegalStateException("balance unavailable"));
        when(economy.withdrawPlayer(player, 25.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE));

        assertEquals(EconomyMutationOutcome.AMBIGUOUS, gateway.withdraw(player, 25.0).outcome());
        verify(economy, times(1)).withdrawPlayer(player, 25.0);
    }

    @Test
    void successStillConfirmsWithoutReadingFinalBalance() {
        when(economy.getBalance(player)).thenReturn(100.0);
        when(economy.withdrawPlayer(player, 25.0)).thenReturn(response(EconomyResponse.ResponseType.SUCCESS));

        assertEquals(EconomyMutationOutcome.CONFIRMED, gateway.withdraw(player, 25.0).outcome());
        verify(economy, times(1)).getBalance(player);
    }

    private EconomyResponse response(EconomyResponse.ResponseType type) {
        return new EconomyResponse(25.0, 75.0, type, "provider response");
    }
}
