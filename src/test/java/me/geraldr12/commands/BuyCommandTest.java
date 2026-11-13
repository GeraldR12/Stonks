package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.BuyTransactionResult;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class BuyCommandTest {
    private Stonks plugin;
    private Player player;
    private Messages messages;
    private int executions;
    private long lastCompanyId;
    private int lastQuantity;

    @BeforeEach
    void setUp() throws IOException {
        plugin = mock(Stonks.class);
        player = mock(Player.class);
        messages = mock(Messages.class);
        when(plugin.getMessages()).thenReturn(messages);
        when(messages.getPluginPrefix()).thenReturn("");
        when(messages.getBoughtActions()).thenReturn("Bought {0}");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-buy-command").toFile());
        when(plugin.getTransactionJournal()).thenReturn(journal);
        MarketTransactionLocks.initialize(journal);
    }

    @Test
    void zeroQuantityRejectedBeforeTransactionService() {
        assertTrue(command().onCommand(player, new String[]{"0", "0"}));
        verify(player).sendMessage(contains("positive"));
        assertTrue(executions == 0);
    }

    @Test
    void companyIdZeroReachesTransactionService() {
        assertTrue(command().onCommand(player, new String[]{"0", "1"}));
        assertTrue(executions == 1);
        assertTrue(lastCompanyId == 0L);
        assertTrue(lastQuantity == 1);
        verify(player, never()).sendMessage(contains("ID and Amount must be positive"));
    }

    @Test
    void negativeCompanyIdRejectedBeforeTransactionService() {
        assertTrue(command().onCommand(player, new String[]{"-1", "1"}));
        verify(player).sendMessage(contains("ID and Amount must be positive"));
        assertTrue(executions == 0);
    }

    @Test
    void positiveCompanyIdReachesTransactionService() {
        assertTrue(command().onCommand(player, new String[]{"1", "1"}));
        assertTrue(executions == 1);
        assertTrue(lastCompanyId == 1L);
        assertTrue(lastQuantity == 1);
    }

    private BuyCommand command() {
        return new BuyCommand(plugin, (playerId, actor, companyId, quantity) -> {
            executions++;
            lastCompanyId = companyId;
            lastQuantity = quantity;
            return BuyTransactionResult.of(BuyTransactionResult.Status.SUCCESS,
                    Math.max(companyId, 1), quantity, "tx");
        });
    }

    @Test
    void negativeQuantityRejectedBeforeTransactionService() {
        assertTrue(command().onCommand(player, new String[]{"1", "-2"}));
        verify(player).sendMessage(contains("positive"));
        verify(plugin, never()).getCompaniesService();
    }

    @Test
    void nonNumericArgumentsAreRejectedBeforeTransactionService() {
        assertTrue(command().onCommand(player, new String[]{"x", "2"}));
        verify(player).sendMessage(contains("numbers"));
        verify(plugin, never()).getCompaniesService();
    }
}
