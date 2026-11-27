package me.geraldr12.integration;

import me.geraldr12.data.services.TreasuryService;
import me.geraldr12.integration.virtualshop.FakeVirtualProduct;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** /shop settled by TNE receipts. Fakes follow ExcellentShop 5.1.7 method names (javap), as in ServerShopBridgeTest. */
public class ServerShopReceiptBridgeTest {
    private static final UUID ID = UUID.fromString("ca74957c-181d-477b-b177-ddd63ad9fa45");
    private static final UUID OTHER = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    @TempDir Path temp;
    private double playerBalance = 500;
    private final List<Runnable> nextTick = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private static Player player(UUID id, boolean online) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(online);
        return player;
    }

    public static class Balance {
        private final double gold;
        public Balance(double gold) { this.gold = gold; }
        public double query(String currency) { return "vault".equals(currency) ? gold : 0; }
    }

    public record Item(Object product) { }

    public static class Prepared {
        final String type; final List<Item> items; final double worth; final Player player;
        public Prepared(String type, List<Item> items, double worth, Player player) {
            this.type = type; this.items = items; this.worth = worth; this.player = player;
        }
        public String getType() { return type; }
        public List<Item> getItemsList() { return items; }
        public Balance calculateWorth() { return new Balance(worth); }
        public boolean isPreview() { return false; }
        public Player getPlayer() { return player; }
    }

    public record Completed(String type, List<Item> items, Balance worth, boolean successful, Player player) { }

    public static class Event implements Cancellable {
        private final Object transaction;
        private boolean cancelled;
        public Event(Object transaction) { this.transaction = transaction; }
        public Object getTransaction() { return transaction; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancel) { cancelled = cancel; }
    }

    static final class Gateway implements TreasuryService.Gateway {
        double balance;
        final List<String> moves = new ArrayList<>();
        @Override public String account() { return "Server_Account"; }
        @Override public boolean deposit(String name, double amount) { balance += amount; moves.add("+" + amount); return true; }
        @Override public int fractionalDigits() { return 0; }
        @Override public double balance(String name) { return balance; }
        @Override public boolean withdraw(String name, double amount) {
            if (balance < amount) return false;
            balance -= amount; moves.add("-" + amount); return true;
        }
    }

    private Path ledger() { return temp.resolve("ledger.yml"); }

    private ServerShopBridge bridge(Gateway gateway, boolean receipts) {
        ServerShopBridge bridge = new ServerShopBridge(new TreasuryService(gateway, ledger()), warnings::add,
                p -> playerBalance, nextTick::add);
        bridge.useReceipts(receipts);
        return bridge;
    }

    private static TneReceiptSource.Receipt receipt(UUID id, UUID participant, boolean ok, String delta) {
        return new TneReceiptSource.Receipt(id, participant, ok, delta == null ? null : new BigDecimal(delta),
                "Vault", false);
    }

    /** Runs a backed admin-shop trade up to MONITOR; returns the completed event (still before the gold moves). */
    private Event trade(ServerShopBridge bridge, String type, double worth, Player player) {
        List<Item> items = List.of(new Item(new FakeVirtualProduct()));
        bridge.onValidated(new Event(new Prepared(type, items, worth, player)));
        Event completed = new Event(new Completed(type, List.copyOf(items), new Balance(worth), true, player));
        bridge.onCompleted(completed);
        bridge.onCompletedMonitor(completed);
        return completed;
    }

    private void tick(double playerDelta) {
        playerBalance += playerDelta;
        List<Runnable> tasks = new ArrayList<>(nextTick);
        nextTick.clear();
        tasks.forEach(Runnable::run);
    }

    private boolean needsReview(Gateway gateway) {
        return new TreasuryService(gateway, ledger()).needsReview();
    }

    @Test
    void purchaseConfirmedByReceiptGoesIntoTheTreasury() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-45"));
        tick(-45);
        assertEquals(List.of("+45.0"), gateway.moves);
        assertFalse(needsReview(gateway));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void saleConfirmedByReceiptIsPaidFromTheTreasury() {
        Gateway gateway = new Gateway();
        gateway.balance = 10;
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "SELL", 6, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "6.00"));
        tick(6);
        assertEquals(List.of("-6.0"), gateway.moves);
        assertFalse(needsReview(gateway));
    }

    @Test
    void failedReceiptLeavesTheTradeForReview() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, false, "0"));
        tick(0);
        assertTrue(gateway.moves.isEmpty());
        assertTrue(needsReview(gateway));
        assertTrue(warnings.getLast().contains("failed"), warnings::toString);
    }

    @Test
    void receiptWithTheWrongAmountLeavesTheTradeForReview() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-44"));
        tick(-45);
        assertTrue(gateway.moves.isEmpty());
        assertTrue(needsReview(gateway));
    }

    @Test
    void receiptThatMovedSeveralCurrenciesIsADivergence() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, null));
        tick(-45);
        assertTrue(needsReview(gateway));
    }

    @Test
    void anotherPlayersReceiptIsIgnored() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), OTHER, true, "-45"));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-45"));
        tick(-45);
        assertEquals(List.of("+45.0"), gateway.moves);
        assertFalse(needsReview(gateway));
    }

    @Test
    void saleWithItemsFromSeveralShopsAddsUpItsReceipts() {
        // Production 19/11: /sellgui with building blocks (worth 0) and bamboo; ExcellentShop paid one transaction
        // per shop, and the first TNE receipt moved 0.
        Gateway gateway = new Gateway();
        gateway.balance = 100;
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "SELL", 26, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "0"));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "26"));
        tick(26);
        assertEquals(List.of("-26.0"), gateway.moves);
        assertFalse(needsReview(gateway));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void purchasePaidInTwoReceiptsIsConfirmedByTheirSum() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-40"));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-5"));
        tick(-45);
        assertEquals(List.of("+45.0"), gateway.moves);
        assertFalse(needsReview(gateway));
    }

    @Test
    void sameReceiptTwiceInOneTradeIsADivergence() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        UUID id = UUID.randomUUID();
        trade(bridge, "BUY", 10, player(ID, true));
        bridge.onReceipt(receipt(id, ID, true, "-5"));
        bridge.onReceipt(receipt(id, ID, true, "-5"));
        tick(-10);
        assertTrue(gateway.moves.isEmpty());
        assertTrue(needsReview(gateway));
    }

    @Test
    void receiptsThatAddUpToMoreThanTheTradeAreADivergence() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-5"));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-45"));
        tick(-50);
        assertTrue(gateway.moves.isEmpty());
        assertTrue(needsReview(gateway));
    }

    @Test
    void laterReceiptOfThePlayerDoesNotUndoTheConfirmation() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-45"));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, false, "3"));
        tick(-42);
        assertEquals(List.of("+45.0"), gateway.moves);
        assertFalse(needsReview(gateway));
    }

    @Test
    void noReceiptLeavesTheTradeForReview() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        tick(-45);
        assertTrue(gateway.moves.isEmpty());
        assertTrue(needsReview(gateway));
        assertTrue(warnings.getLast().contains("no TNE receipt"), warnings::toString);
    }

    @Test
    void receiptFromAnotherThreadIsNotAccepted() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(new TneReceiptSource.Receipt(UUID.randomUUID(), ID, true, new BigDecimal("-45"), "Vault", true));
        tick(-45);
        assertTrue(gateway.moves.isEmpty());
        assertTrue(needsReview(gateway));
    }

    @Test
    void saleCancelledAfterHighestReturnsTheReservation() {
        Gateway gateway = new Gateway();
        gateway.balance = 100;
        ServerShopBridge bridge = bridge(gateway, true);
        List<Item> items = List.of(new Item(new FakeVirtualProduct()));
        Player player = player(ID, true);
        bridge.onValidated(new Event(new Prepared("SELL", items, 25, player)));
        Event completed = new Event(new Completed("SELL", List.copyOf(items), new Balance(25), true, player));
        bridge.onCompleted(completed);
        completed.setCancelled(true); // a later listener cancelled it before MONITOR
        bridge.onCompletedMonitor(completed);
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "25"));
        tick(0);
        assertEquals(List.of("-25.0", "+25.0"), gateway.moves);
        assertFalse(needsReview(gateway));
    }

    @Test
    void cancelledTradeWhoseBalanceMovedAnywayNeedsReview() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        List<Item> items = List.of(new Item(new FakeVirtualProduct()));
        Player player = player(ID, true);
        bridge.onValidated(new Event(new Prepared("BUY", items, 25, player)));
        Event completed = new Event(new Completed("BUY", List.copyOf(items), new Balance(25), true, player));
        bridge.onCompleted(completed);
        completed.setCancelled(true);
        tick(-25);
        assertTrue(needsReview(gateway));
    }

    @Test
    void repeatedReceiptIdDoesNotSettleASecondTrade() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        UUID receiptId = UUID.randomUUID();
        trade(bridge, "BUY", 10, player(ID, true));
        bridge.onReceipt(receipt(receiptId, ID, true, "-10"));
        tick(-10);
        assertFalse(needsReview(gateway));
        trade(bridge, "BUY", 10, player(ID, true));
        bridge.onReceipt(receipt(receiptId, ID, true, "-10"));
        tick(-10);
        assertEquals(List.of("+10.0"), gateway.moves);
        assertTrue(needsReview(gateway));
        assertTrue(warnings.getLast().contains(receiptId.toString()), warnings::toString);
    }

    @Test
    void offlinePlayerIsRefusedBeforeAnythingMoves() {
        Gateway gateway = new Gateway();
        gateway.balance = 100;
        ServerShopBridge bridge = bridge(gateway, true);
        Event completed = trade(bridge, "SELL", 6, player(ID, false));
        assertTrue(completed.isCancelled());
        tick(0);
        assertTrue(gateway.moves.isEmpty());
        assertFalse(needsReview(gateway));
    }

    @Test
    void withoutTneTheBalanceObservationStillDecides() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, false);
        trade(bridge, "BUY", 45, player(ID, true));
        bridge.onReceipt(receipt(UUID.randomUUID(), ID, true, "-1")); // ignored without TNE
        tick(-45);
        assertEquals(List.of("+45.0"), gateway.moves);
        assertFalse(needsReview(gateway));
    }

    @Test
    void restartKeepsTheOpenTradeAndTheUsedReceipts() throws Exception {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway, true);
        UUID used = UUID.randomUUID();
        trade(bridge, "BUY", 10, player(ID, true));
        bridge.onReceipt(receipt(used, ID, true, "-10"));
        tick(-10);
        trade(bridge, "BUY", 10, player(ID, true));
        tick(-10); // no receipt: stays open in the ledger
        assertTrue(Files.readString(ledger()).contains(used.toString()));

        ServerShopBridge afterRestart = bridge(gateway, true);
        Event next = trade(afterRestart, "BUY", 10, player(ID, true));
        assertTrue(next.isCancelled());
        assertEquals(List.of("+10.0"), gateway.moves);
        assertTrue(needsReview(gateway));
    }

    @Test
    void receiptedFinishNeedsTheOpenSettlement() {
        Gateway gateway = new Gateway();
        TreasuryService treasury = new TreasuryService(gateway, ledger());
        var open = new TreasuryService.ShopSettlement("Server_Account", ID.toString(), "BUY", 10, 500);
        assertTrue(treasury.beginShop(open));
        var copy = new TreasuryService.ShopSettlement("Server_Account", ID.toString(), "BUY", 10, 500);
        assertFalse(treasury.finishShopReceipted(copy, UUID.randomUUID()));
        assertFalse(treasury.finishShopReceipted(open, (UUID) null));
        assertTrue(treasury.finishShopReceipted(open, UUID.randomUUID()));
        assertFalse(treasury.needsReview());
    }
}
