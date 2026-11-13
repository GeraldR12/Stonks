package me.geraldr12.integration;

import me.geraldr12.data.services.TreasuryService;
import me.geraldr12.integration.virtualshop.FakeVirtualProduct;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fakes follow ExcellentShop 5.1.7 method names (javap). */
public class ServerShopBridgeTest {
    @TempDir Path temp;

    public static class Balance {
        private final double gold;
        public Balance(double gold) { this.gold = gold; }
        public double query(String currency) { return "vault".equals(currency) ? gold : 0; }
    }

    public static class ChestProduct { }

    public record Item(Object product) { }

    public static class Prepared {
        final String type; final List<Item> items; final double worth; final boolean preview;
        public Prepared(String type, List<Item> items, double worth, boolean preview) {
            this.type = type; this.items = items; this.worth = worth; this.preview = preview;
        }
        public String getType() { return type; }
        public List<Item> getItemsList() { return items; }
        public Balance calculateWorth() { return new Balance(worth); }
        public boolean isPreview() { return preview; }
        public Object getPlayer() { return null; }
    }

    public record Completed(String type, List<Item> items, Balance worth, boolean successful) { }

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

    private final List<Item> admin = List.of(new Item(new FakeVirtualProduct()));
    private final List<Item> chest = List.of(new Item(new ChestProduct()));

    private ServerShopBridge bridge(Gateway gateway) {
        return new ServerShopBridge(new TreasuryService(gateway, temp.resolve("ledger.yml")), message -> { });
    }

    @Test
    void saleTheTreasuryCannotCoverIsRefused() {
        Gateway gateway = new Gateway();
        gateway.balance = 5;
        Event event = new Event(new Prepared("SELL", admin, 6, false));
        bridge(gateway).onValidated(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void coveredSaleGoesThroughAndIsPaidFromTheTreasury() {
        Gateway gateway = new Gateway();
        gateway.balance = 10;
        ServerShopBridge bridge = bridge(gateway);
        Event validated = new Event(new Prepared("SELL", admin, 6, false));
        bridge.onValidated(validated);
        assertFalse(validated.isCancelled());
        bridge.onCompleted(new Event(new Completed("SELL", admin, new Balance(6), true)));
        assertEquals(4, gateway.balance);
        assertEquals(List.of("-6.0"), gateway.moves);
    }

    @Test
    void fractionalSalePaysWhatTheEconomyMoves() {
        Gateway gateway = new Gateway();
        gateway.balance = 10;
        bridge(gateway).onCompleted(new Event(new Completed("SELL", admin, new Balance(2.91), true)));
        assertEquals(List.of("-2.0"), gateway.moves);
    }

    @Test
    void purchaseGoesIntoTheTreasury() {
        Gateway gateway = new Gateway();
        bridge(gateway).onCompleted(new Event(new Completed("BUY", admin, new Balance(450), true)));
        assertEquals(450, gateway.balance);
    }

    /** ExcellentShop 5.1.7 fires validated and completed for the price preview, then again for the real trade. */
    @Test
    void previewThenPurchaseCreditsOnce() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway);
        List<Item> previewItems = List.of(new Item(new FakeVirtualProduct()));
        bridge.onValidated(new Event(new Prepared("BUY", previewItems, 35, true)));
        bridge.onCompleted(new Event(new Completed("BUY", List.copyOf(previewItems), new Balance(35), true)));
        List<Item> realItems = List.of(new Item(new FakeVirtualProduct()));
        bridge.onValidated(new Event(new Prepared("BUY", realItems, 35, false)));
        bridge.onCompleted(new Event(new Completed("BUY", List.copyOf(realItems), new Balance(35), true)));
        assertEquals(List.of("+35.0"), gateway.moves);
    }

    @Test
    void sellPreviewDoesNotPayAndTheRealSaleDoes() {
        Gateway gateway = new Gateway();
        gateway.balance = 100;
        ServerShopBridge bridge = bridge(gateway);
        List<Item> previewItems = List.of(new Item(new FakeVirtualProduct()));
        bridge.onValidated(new Event(new Prepared("SELL", previewItems, 28, true)));
        bridge.onCompleted(new Event(new Completed("SELL", List.copyOf(previewItems), new Balance(28), true)));
        assertTrue(gateway.moves.isEmpty());
        List<Item> realItems = List.of(new Item(new FakeVirtualProduct()));
        bridge.onValidated(new Event(new Prepared("SELL", realItems, 28, false)));
        bridge.onCompleted(new Event(new Completed("SELL", List.copyOf(realItems), new Balance(28), true)));
        assertEquals(List.of("-28.0"), gateway.moves);
    }

    @Test
    void playerChestShopsPreviewsAndFailuresAreIgnored() {
        Gateway gateway = new Gateway();
        ServerShopBridge bridge = bridge(gateway);
        Event chestSale = new Event(new Prepared("SELL", chest, 6, false));
        bridge.onValidated(chestSale);
        assertFalse(chestSale.isCancelled());
        Event preview = new Event(new Prepared("SELL", admin, 6, true));
        bridge.onValidated(preview);
        assertFalse(preview.isCancelled());
        bridge.onCompleted(new Event(new Completed("BUY", chest, new Balance(450), true)));
        bridge.onCompleted(new Event(new Completed("BUY", admin, new Balance(450), false)));
        bridge.onValidated(new Object());
        bridge.onCompleted(new Object());
        assertTrue(gateway.moves.isEmpty());
    }
}
