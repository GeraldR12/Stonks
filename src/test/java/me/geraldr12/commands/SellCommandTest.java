package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.SellTransactionResult;
import me.geraldr12.utils.Messages;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SellCommandTest {

    private Stonks plugin;
    private Messages messages;
    private Player player;
    private UUID playerId;
    private SellCommand.SellExecutor executor;
    private SellCommand command;

    @BeforeEach
    void setUp() {
        plugin = mock(Stonks.class);
        messages = mock(Messages.class);
        player = mock(Player.class);
        playerId = UUID.randomUUID();
        executor = mock(SellCommand.SellExecutor.class);

        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getCompaniesService()).thenReturn(mock(me.geraldr12.data.services.CompaniesService.class));
        when(messages.getPluginPrefix()).thenReturn("");
        when(messages.getSoldActions()).thenReturn("Sold {0}");
        when(messages.getInsufficientActions()).thenReturn("Insufficient shares.");
        when(player.getUniqueId()).thenReturn(playerId);

        command = new SellCommand(plugin, executor);
    }

    @Test
    void validInvocationCallsExecutorOnceWithPlayerAsEconomyActor() {
        SellTransactionResult result = result(SellTransactionResult.Status.SUCCESS, 7, 3);
        when(executor.execute(playerId, player, 7L, 3)).thenReturn(result);

        assertTrue(command.onCommand(player, new String[]{"7", "3"}));

        verify(executor).execute(playerId, player, 7L, 3);
        verifyNoMoreInteractions(executor);
    }

    @Test
    void companyIdZeroIsAccepted() {
        when(executor.execute(playerId, player, 0L, 1))
                .thenReturn(result(SellTransactionResult.Status.VALIDATION_REJECTED, 0, 1));

        command.onCommand(player, new String[]{"0", "1"});

        verify(executor).execute(playerId, player, 0L, 1);
        verify(player).sendMessage("Insufficient shares.");
    }

    @Test
    void missingArgumentsPreserveUsageAndDoNotExecute() {
        command.onCommand(player, new String[]{"1"});

        verify(player).sendMessage("Usage: /invest sell <id> <amount>");
        verifyNoInteractions(executor);
    }

    @Test
    void malformedCompanyIdPreservesNumberMessage() {
        command.onCommand(player, new String[]{"bad", "1"});

        verify(player).sendMessage("Error: ID and Amount must be numbers.");
        verifyNoInteractions(executor);
    }

    @Test
    void malformedQuantityPreservesNumberMessage() {
        command.onCommand(player, new String[]{"1", "bad"});

        verify(player).sendMessage("Error: ID and Amount must be numbers.");
        verifyNoInteractions(executor);
    }

    @Test
    void negativeCompanyIdIsDelegatedToService() {
        when(executor.execute(playerId, player, -1L, 1))
                .thenReturn(result(SellTransactionResult.Status.VALIDATION_REJECTED, 0, 1));

        command.onCommand(player, new String[]{"-1", "1"});

        verify(executor).execute(playerId, player, -1L, 1);
        verify(player).sendMessage("Insufficient shares.");
    }

    @Test
    void nonPositiveQuantitiesAreDelegatedToService() {
        when(executor.execute(eq(playerId), eq(player), eq(1L), anyInt()))
                .thenReturn(result(SellTransactionResult.Status.VALIDATION_REJECTED, 1, 0));

        command.onCommand(player, new String[]{"1", "0"});
        command.onCommand(player, new String[]{"1", "-1"});

        verify(executor).execute(playerId, player, 1L, 0);
        verify(executor).execute(playerId, player, 1L, -1);
        verify(player, times(2)).sendMessage("Insufficient shares.");
    }

    @Test
    void mapsAllStatuses() {
        assertMessage(SellTransactionResult.Status.SUCCESS, "Sold 3", 3);
        assertMessage(SellTransactionResult.Status.VALIDATION_REJECTED, "Insufficient shares.", 3);
        assertMessage(SellTransactionResult.Status.DEFINITE_FAILURE, "Unable to complete the sale.", 3);
        assertMessage(SellTransactionResult.Status.COMPENSATED,
                "Sale could not be completed; your shares were restored.", 3);
        assertMessage(SellTransactionResult.Status.RECONCILIATION_REQUIRED,
                "CRITICAL: sale state requires administrator reconciliation.", 3);
        assertMessage(SellTransactionResult.Status.MARKET_IMPACT_FAILURE_AFTER_COMMIT,
                "Sale completed; market update requires administrator reconciliation.", 3);
    }

    @Test
    void unresolvedStatusesDoNotEncourageRetry() {
        for (SellTransactionResult.Status status : new SellTransactionResult.Status[]{
                SellTransactionResult.Status.RECONCILIATION_REQUIRED,
                SellTransactionResult.Status.MARKET_IMPACT_FAILURE_AFTER_COMMIT}) {
            reset(executor);
            clearInvocations(player);
            when(executor.execute(eq(playerId), eq(player), eq(1L), eq(2)))
                    .thenReturn(result(status, 1, 2));

            command.onCommand(player, new String[]{"1", "2"});

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            String message = captor.getValue().toLowerCase();
            assertFalse(message.contains("try again"));
            assertFalse(message.contains("retry"));
            assertFalse(message.contains("sell again"));
            if (status == SellTransactionResult.Status.MARKET_IMPACT_FAILURE_AFTER_COMMIT) {
                assertTrue(message.contains("sale completed"));
            }
        }
    }

    @Test
    void tabCompletionPreservesCompanyAndQuantitySuggestions() {
        var companyOne = mock(me.geraldr12.data.dao.CompanyDao.class);
        var companyTwo = mock(me.geraldr12.data.dao.CompanyDao.class);
        when(companyOne.getId()).thenReturn(1);
        when(companyTwo.getId()).thenReturn(0);
        when(plugin.getCompaniesService().getAllCompanies()).thenReturn(List.of(companyOne, companyTwo));

        assertEquals(List.of("1", "0"), command.onTabComplete(player, new String[]{""}));
        assertEquals(List.of("1", "10", "100"), command.onTabComplete(player, new String[]{"1", ""}));
    }

    private void assertMessage(SellTransactionResult.Status status, String expected, int quantity) {
        reset(executor);
        clearInvocations(player);
        when(executor.execute(eq(playerId), eq(player), eq(1L), eq(quantity)))
                .thenReturn(result(status, 1, quantity));

        command.onCommand(player, new String[]{"1", String.valueOf(quantity)});

        verify(player).sendMessage(expected);
    }

    private SellTransactionResult result(SellTransactionResult.Status status, long companyId, int quantity) {
        return SellTransactionResult.of(status, Math.max(companyId, 0), Math.max(quantity, 1), "tx");
    }
}
