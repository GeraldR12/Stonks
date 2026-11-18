package me.geraldr12.integration;

import me.geraldr12.data.services.TreasuryService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/**
 * Makes ExcellentShop's admin shop ({@code /shop}, the "virtual shop") trade against the server treasury:
 * gold a player gets for selling comes out of the treasury account, and gold a player spends goes into it.
 * A sale the treasury cannot cover is refused before anything changes hands.
 *
 * <p>ExcellentShop is reached only by reflection (5.1.7 checked with javap): the plugin loads without it.
 * Player chest shops (ExcellentShop's own "chest_shop" module) are left alone; they already trade between players.</p>
 *
 * <p>With TNE receipts ({@link TneReceiptSource}) the player's side is settled by TNE itself: once the trade can no
 * longer be cancelled (MONITOR), the first TNE receipt for that player in the same call stack decides it, and it
 * confirms only a successful transaction that moved exactly the trade's gold. Without TNE the bridge falls back to
 * comparing the player's balance on the next tick. Items are outside this contract: nothing proves them
 * synchronously.</p>
 */
public class ServerShopBridge implements Listener {

    static final String VALIDATED = "su.nightexpress.excellentshop.api.event.TransactionValidatedEvent";
    static final String COMPLETED = "su.nightexpress.excellentshop.api.event.TransactionCompletedEvent";
    static final String CURRENCY = "vault";
    static final String OUT_OF_GOLD = "The server shop is out of gold right now. Try again later.";
    static final String TEMPORARILY_UNAVAILABLE = "The server shop cannot settle this trade right now. Try again later.";
    static final String BALANCE_OBSERVATION = "Server shop treasury uses balance observation (no TNE receipts); "
            + "unrestricted release is not recommended.";

    private final TreasuryService treasury;
    private final Consumer<String> warn;
    private final ToDoubleFunction<Player> balance;
    private final Consumer<Runnable> nextTick;
    private final IdentityHashMap<Object, Boolean> validatedItems = new IdentityHashMap<>();
    /** Trades accepted at HIGHEST, by event, until the next tick settles them. Main thread only. */
    private final IdentityHashMap<Object, Wait> openTrades = new IdentityHashMap<>();
    /** Trades that reached MONITOR uncancelled and wait for the player's TNE receipt. Main thread only. */
    private final Map<UUID, Wait> armed = new HashMap<>();
    private boolean receipts;

    /** One backed trade on its way through ExcellentShop. */
    private static final class Wait {
        final TreasuryService.ShopSettlement settlement;
        final UUID player;
        final BigDecimal expected;
        boolean armed;
        UUID confirmed;
        String divergence;

        Wait(TreasuryService.ShopSettlement settlement, UUID player) {
            this.settlement = settlement;
            this.player = player;
            BigDecimal amount = BigDecimal.valueOf(settlement.amount());
            this.expected = "BUY".equals(settlement.direction()) ? amount.negate() : amount;
        }
    }

    public ServerShopBridge(TreasuryService treasury, Consumer<String> warn,
                            ToDoubleFunction<Player> balance, Consumer<Runnable> nextTick) {
        this.treasury = treasury;
        this.warn = warn;
        this.balance = balance;
        this.nextTick = nextTick;
    }

    /** Settles trades by TNE receipt instead of by balance. Call before {@link #register}. */
    public void useReceipts(boolean active) {
        this.receipts = active;
    }

    /** Registers on ExcellentShop's events if the plugin is present. Returns whether it is active. */
    public boolean register(Plugin plugin) {
        Plugin shop = Bukkit.getPluginManager().getPlugin("ExcellentShop");
        if (shop == null || !shop.isEnabled() || treasury == null || !treasury.enabled()) return false;
        try {
            ClassLoader loader = shop.getClass().getClassLoader();
            Class<? extends Event> validated = Class.forName(VALIDATED, true, loader).asSubclass(Event.class);
            Class<? extends Event> completed = Class.forName(COMPLETED, true, loader).asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(validated, this, EventPriority.HIGH,
                    (listener, event) -> {
                        if (event.getClass() == validated) onValidated(event);
                    }, plugin, true);
            Bukkit.getPluginManager().registerEvent(completed, this, EventPriority.HIGHEST,
                    (listener, event) -> {
                        if (event.getClass() == completed) onCompleted(event);
                    }, plugin, true);
            Bukkit.getPluginManager().registerEvent(completed, this, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (event.getClass() == completed) onCompletedMonitor(event);
                    }, plugin, true);
            if (!receipts) warn.accept(BALANCE_OBSERVATION);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            HandlerList.unregisterAll(this);
            warn.accept("Server shop is not linked to the treasury (ExcellentShop API not recognised): " + e);
            return false;
        }
    }

    /** Before the trade: refuse an admin-shop sale the treasury cannot pay. */
    void onValidated(Object event) {
        try {
            Object transaction = call(event, "getTransaction");
            if (transaction == null) return;
            List<?> items = (List<?>) call(transaction, "getItemsList");
            if (!isAdminShop(items)) return;
            Object first = items.getFirst();
            boolean preview = Boolean.TRUE.equals(call(transaction, "isPreview"));
            validatedItems.put(first, preview);
            nextTick.accept(() -> validatedItems.remove(first));
            if (preview) return;
            if (!"SELL".equals(String.valueOf(call(transaction, "getType")))) return;
            double worth = treasury.toEconomy(gold(call(transaction, "calculateWorth")));
            if (worth > 0 && !treasury.canPay(worth)) {
                ((Cancellable) event).setCancelled(true);
                Object player = call(transaction, "getPlayer");
                if (player instanceof Player p) {
                    p.sendMessage(treasury.needsReview() ? TEMPORARILY_UNAVAILABLE : OUT_OF_GOLD);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn.accept("Could not validate server shop trade: " + e);
        }
    }

    /** ExcellentShop 5.1.7 still allows cancellation here, before it moves items or player gold. */
    void onCompleted(Object event) {
        try {
            if (!(event instanceof Cancellable cancellable) || cancellable.isCancelled()) return;
            Object transaction = call(event, "getTransaction");
            if (transaction == null || !Boolean.TRUE.equals(call(transaction, "successful"))) return;
            List<?> items = (List<?>) call(transaction, "items");
            if (!isAdminShop(items)) return;
            double worth = treasury.toEconomy(gold(call(transaction, "worth")));
            if (!(worth > 0)) return;
            Boolean preview = validatedItems.remove(items.getFirst());
            if (Boolean.TRUE.equals(preview)) return;
            String type = String.valueOf(call(transaction, "type"));
            if (!(call(transaction, "player") instanceof Player player) || preview == null
                    || !("BUY".equals(type) || "SELL".equals(type))) {
                cancellable.setCancelled(true);
                warn.accept("Server shop trade cancelled because its preview state or participant could not be verified.");
                return;
            }
            // NightCore pays an offline player on another thread, where no receipt or balance check can follow it.
            if (treasury.needsReview() || !player.isOnline()) {
                cancellable.setCancelled(true);
                player.sendMessage(TEMPORARILY_UNAVAILABLE);
                return;
            }
            var settlement = new TreasuryService.ShopSettlement(
                    treasury.account(), player.getUniqueId().toString(), type, worth, balance.applyAsDouble(player));
            if (!treasury.beginShop(settlement)) {
                cancellable.setCancelled(true);
                player.sendMessage("SELL".equals(type) && !treasury.needsReview()
                        ? OUT_OF_GOLD : TEMPORARILY_UNAVAILABLE);
                return;
            }
            Wait wait = new Wait(settlement, player.getUniqueId());
            openTrades.put(event, wait);
            nextTick.accept(() -> settle(cancellable, player, wait));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (event instanceof Cancellable cancellable) cancellable.setCancelled(true);
            warn.accept("Server shop trade could not be matched in the treasury: " + e);
        }
    }

    /** No plugin can cancel after MONITOR: ExcellentShop now moves the gold in this same call stack. */
    void onCompletedMonitor(Object event) {
        if (!receipts || !(event instanceof Cancellable cancellable) || cancellable.isCancelled()) return;
        Wait wait = openTrades.get(event);
        if (wait == null) return;
        Wait previous = armed.put(wait.player, wait);
        if (previous != null) previous.divergence = "a second trade started before its TNE receipt arrived";
        wait.armed = true;
    }

    /** The first TNE receipt for an armed player decides that trade; receipts for anyone else are ignored. */
    public void onReceipt(TneReceiptSource.Receipt receipt) {
        // Off the server thread it is not our call stack: it cannot be this trade and must not touch the maps.
        if (receipt == null || receipt.participant() == null || receipt.offThread()) return;
        Wait wait = armed.remove(receipt.participant());
        if (wait == null) return;
        if (!receipt.successful()) {
            wait.divergence = "TNE reported the transaction as failed";
        } else if (receipt.delta() == null || receipt.delta().compareTo(wait.expected) != 0) {
            wait.divergence = "TNE moved " + receipt.delta() + " instead of " + wait.expected;
        } else {
            wait.confirmed = receipt.receiptId();
        }
    }

    private void settle(Cancellable event, Player player, Wait wait) {
        openTrades.remove(event);
        armed.remove(wait.player, wait);
        TreasuryService.ShopSettlement settlement = wait.settlement;
        String reason;
        try {
            if (!receipts) {
                if (treasury.finishShop(settlement, event.isCancelled(), balance.applyAsDouble(player))) return;
                reason = "the player's balance did not change as expected";
            } else if (wait.divergence != null) {
                reason = wait.divergence;
            } else if (wait.confirmed != null) {
                if (treasury.finishShopReceipted(settlement, wait.confirmed)) return;
                reason = "TNE receipt " + wait.confirmed + " could not be recorded (repeated, or storage failed)";
            } else if (!wait.armed && event.isCancelled()) {
                if (treasury.finishShop(settlement, true, balance.applyAsDouble(player))) return;
                reason = "the trade was cancelled but the player's balance changed";
            } else {
                reason = "no TNE receipt arrived for the trade";
            }
        } catch (RuntimeException e) {
            reason = "settlement failed: " + e;
        }
        warn.accept("Server shop settlement needs review for " + settlement.player() + " (" + reason
                + "). Inspect treasury/ledger.yml before resuming backed trades.");
    }

    static boolean isAdminShop(List<?> items) throws ReflectiveOperationException {
        if (items == null || items.isEmpty()) return false;
        for (Object item : items) {
            Object product = call(item, "product");
            if (product == null || !product.getClass().getName().contains(".virtualshop.")) return false;
        }
        return true;
    }

    static double gold(Object balanceHolder) throws ReflectiveOperationException {
        if (balanceHolder == null) return 0;
        Object value = balanceHolder.getClass().getMethod("query", String.class).invoke(balanceHolder, CURRENCY);
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : 0;
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        var method = target.getClass().getMethod(name);
        try {
            return method.invoke(target);
        } catch (IllegalAccessException e) {
            method.setAccessible(true);
            return method.invoke(target);
        }
    }
}
