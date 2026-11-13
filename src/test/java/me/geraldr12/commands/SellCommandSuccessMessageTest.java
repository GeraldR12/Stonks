package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.SellTransactionResult;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SellCommandSuccessMessageTest {
    private Stonks plugin;
    private Player player;
    private SellCommand.SellExecutor executor;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        plugin = mock(Stonks.class);
        player = mock(Player.class);
        executor = mock(SellCommand.SellExecutor.class);
        playerId = UUID.randomUUID();
        Messages messages = mock(Messages.class);
        when(plugin.getMessages()).thenReturn(messages);
        when(messages.getPluginPrefix()).thenReturn("Stonks > ");
        when(messages.getSoldActions()).thenReturn("You have sold {0} stocks for {1}.");
        when(player.getUniqueId()).thenReturn(playerId);
    }

    @Test
    void successShowsFormattedTotalValue() {
        String message = sell(146.0);

        assertFalse(message.contains("{1}"));
        assertTrue(message.contains(FormattingUtils.formatDouble(146.0)));
        assertEquals("Stonks > You have sold 2 stocks for "
                + FormattingUtils.formatDouble(146.0) + ".", message);
    }

    @Test
    void successWithNaNShowsUnknownValue() {
        assertUnknownValue(Double.NaN);
    }

    @Test
    void successWithPositiveInfinityShowsUnknownValue() {
        assertUnknownValue(Double.POSITIVE_INFINITY);
    }

    @Test
    void successWithNegativeInfinityShowsUnknownValue() {
        assertUnknownValue(Double.NEGATIVE_INFINITY);
    }

    private void assertUnknownValue(double value) {
        String message = sell(value);

        assertFalse(message.contains("{1}"));
        assertEquals("Stonks > You have sold 2 stocks for ?.", message);
    }

    private String sell(double value) {
        when(executor.execute(playerId, player, 0L, 2)).thenReturn(
                new SellTransactionResult(SellTransactionResult.Status.SUCCESS, 0, 2, "tx", value));

        assertTrue(new SellCommand(plugin, executor).onCommand(player, new String[]{"0", "2"}));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(player).sendMessage(sent.capture());
        verify(executor).execute(playerId, player, 0L, 2);
        verifyNoMoreInteractions(executor);
        return sent.getValue();
    }
}
