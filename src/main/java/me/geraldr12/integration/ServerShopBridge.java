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

import java.util.List;
import java.util.function.Consumer;

/**
 * Makes ExcellentShop's admin shop ({@code /shop}, the "virtual shop") trade against the server treasury:
 * gold a player gets for selling comes out of the treasury account, and gold a player spends goes into it.
 * A sale the treasury cannot cover is refused before anything changes hands.
 *
 * <p>ExcellentShop is reached only by reflection (5.1.7 checked with javap): the plugin loads without it.
 * Player chest shops (ExcellentShop's own "chest_shop" module) are left alone; they already trade between players.</p>
 */
public class ServerShopBridge implements Listener {

    static final String VALIDATED = "su.nightexpress.excellentshop.api.event.TransactionValidatedEvent";
    static final String COMPLETED = "su.nightexpress.excellentshop.api.event.TransactionCompletedEvent";
    static final String CURRENCY = "vault";
    static final String OUT_OF_GOLD = "The server shop is out of gold right now. Try again later.";

    private final TreasuryService treasury;
    private final Consumer<String> warn;
    /**
     * First item of the last validated transaction when it was only a preview (the price shown in the
     * buy or sell menu before the player confirms). ExcellentShop fires the completed event for previews
     * too, and the completed transaction has no preview flag, but it carries the same item objects.
     */
    private Object previewItem;

    public ServerShopBridge(TreasuryService treasury, Consumer<String> warn) {
        this.treasury = treasury;
        this.warn = warn;
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
            Bukkit.getPluginManager().registerEvent(completed, this, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (event.getClass() == completed) onCompleted(event);
                    }, plugin, true);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            HandlerList.unregisterAll(this);
            warn.accept("Server shop is not linked to the treasury (ExcellentShop API not recognised): " + e);
            return false;
        }
    }

    /** Before the trade: refuse an admin-shop sale the treasury cannot pay. */
    void onValidated(Object event) {
        previewItem = null;
        try {
            Object transaction = call(event, "getTransaction");
            if (transaction == null) return;
            List<?> items = (List<?>) call(transaction, "getItemsList");
            if (Boolean.TRUE.equals(call(transaction, "isPreview"))) {
                if (items != null && !items.isEmpty()) previewItem = items.get(0);
                return;
            }
            if (!"SELL".equals(String.valueOf(call(transaction, "getType")))) return;
            if (!isAdminShop(items)) return;
            double worth = treasury.toEconomy(gold(call(transaction, "calculateWorth")));
            if (worth > 0 && !treasury.canPay(worth)) {
                ((Cancellable) event).setCancelled(true);
                Object player = call(transaction, "getPlayer");
                if (player instanceof Player p) p.sendMessage(OUT_OF_GOLD);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Unknown shape: refuse nothing. The completed handler still records what happened.
        }
    }

    /** After the trade: move the same gold into or out of the treasury. */
    void onCompleted(Object event) {
        Object preview = previewItem;
        previewItem = null;
        try {
            if (event instanceof Cancellable c && c.isCancelled()) return;
            Object transaction = call(event, "getTransaction");
            if (transaction == null || !Boolean.TRUE.equals(call(transaction, "successful"))) return;
            List<?> items = (List<?>) call(transaction, "items");
            if (preview != null && items != null && !items.isEmpty() && items.get(0) == preview) return;
            if (!isAdminShop(items)) return;
            double worth = gold(call(transaction, "worth"));
            if (!(worth > 0)) return;
            // The economy only moves whole units here, so match what the player actually got or paid.
            worth = treasury.toEconomy(worth);
            if (!(worth > 0)) return;
            String type = String.valueOf(call(transaction, "type"));
            if (type.equals("SELL")) {
                treasury.pay(worth);
            } else if (type.equals("BUY")) {
                treasury.credit(worth);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn.accept("Server shop trade could not be matched in the treasury: " + e);
        }
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
