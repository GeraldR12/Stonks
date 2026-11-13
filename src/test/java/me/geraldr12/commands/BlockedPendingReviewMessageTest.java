package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.BuyTransactionResult;
import me.geraldr12.data.services.SellTransactionResult;
import me.geraldr12.utils.Messages;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class BlockedPendingReviewMessageTest {
    private static final String BLOCKED_MESSAGE = "Trading is paused for this company or your account: an earlier transaction is awaiting administrator review. Nothing was charged.";

    @Test
    void blockedBuyExplainsEarlierTransactionWithoutCriticalWarning() {
        Stonks plugin = plugin();
        Player player = player();
        BuyCommand command = new BuyCommand(plugin, (id, actor, company, quantity) ->
                BuyTransactionResult.of(BuyTransactionResult.Status.valueOf("BLOCKED_PENDING_REVIEW"), company, quantity, null));

        assertTrue(command.onCommand(player, new String[]{"7", "2"}));

        verify(player).sendMessage(BLOCKED_MESSAGE);
    }

    @Test
    void blockedSellExplainsEarlierTransactionWithoutCriticalWarning() {
        Stonks plugin = plugin();
        Player player = player();
        SellCommand command = new SellCommand(plugin, (id, actor, company, quantity) ->
                SellTransactionResult.of(SellTransactionResult.Status.valueOf("BLOCKED_PENDING_REVIEW"), company, quantity, null));

        assertTrue(command.onCommand(player, new String[]{"7", "2"}));

        verify(player).sendMessage(BLOCKED_MESSAGE);
    }

    @Test
    void postEffectBuyReconciliationStillUsesCriticalMessage() {
        Stonks plugin = plugin();
        Player player = player();
        BuyCommand command = new BuyCommand(plugin, (id, actor, company, quantity) ->
                BuyTransactionResult.of(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, company, quantity, "transaction"));

        command.onCommand(player, new String[]{"7", "2"});

        verify(player).sendMessage("CRITICAL: purchase state requires administrator reconciliation.");
    }

    @Test
    void postEffectSellReconciliationStillUsesCriticalMessage() {
        Stonks plugin = plugin();
        Player player = player();
        SellCommand command = new SellCommand(plugin, (id, actor, company, quantity) ->
                SellTransactionResult.of(SellTransactionResult.Status.RECONCILIATION_REQUIRED, company, quantity, "transaction"));

        command.onCommand(player, new String[]{"7", "2"});

        verify(player).sendMessage("CRITICAL: sale state requires administrator reconciliation.");
    }

    private Stonks plugin() {
        Stonks plugin = mock(Stonks.class);
        Messages messages = mock(Messages.class);
        when(plugin.getMessages()).thenReturn(messages);
        when(messages.getPluginPrefix()).thenReturn("");
        return plugin;
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }
}
