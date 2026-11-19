package me.geraldr12.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Listens to The New Economy's {@code transaction_post} callback and hands each participant of a finished
 * TNE transaction to the server shop bridge as a plain {@link Receipt}.
 *
 * <p>TNE is reached only by reflection (TNE-Paper 0.1.5.3 checked with javap): the plugin loads without it.
 * TNE fires the callback synchronously inside {@code Transaction.process()}, also for failed transactions, and
 * offers no way to remove a consumer, so {@link #unregister()} only silences this one.</p>
 */
public final class TneReceiptSource {

    static final String PLUGIN = "TheNewEconomy";
    static final String CORE = "net.tnemc.plugincore.PluginCore";
    static final String CALLBACK = "transaction_post";

    /**
     * One participant of a finished TNE transaction.
     *
     * @param delta ending minus starting holdings in the one currency that changed; zero when none changed,
     *              {@code null} when several currencies changed or the holdings could not be read
     * @param offThread the callback did not run on the server thread, so it cannot belong to a /shop trade
     */
    public record Receipt(UUID receiptId, UUID participant, boolean successful, BigDecimal delta,
                          String source, boolean offThread) {
    }

    private final Consumer<String> warn;
    private final BooleanSupplier primaryThread;
    private volatile Consumer<Receipt> delivery;
    private volatile boolean active;

    public TneReceiptSource(Consumer<String> warn, BooleanSupplier primaryThread) {
        this.warn = warn;
        this.primaryThread = primaryThread;
    }

    /** Hooks into TNE when the {@value #PLUGIN} plugin is enabled. Returns whether receipts will arrive. */
    public boolean register(Plugin plugin, Consumer<Receipt> delivery) {
        try {
            Plugin tne = Bukkit.getPluginManager().getPlugin(PLUGIN);
            if (tne == null || !tne.isEnabled()) return false;
            Object callbacks = Class.forName(CORE, true, tne.getClass().getClassLoader())
                    .getMethod("callbacks").invoke(null);
            return attach(callbacks, delivery);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            warn.accept("TNE receipts are not available (TNE API not recognised): " + e);
            return false;
        }
    }

    /** Adds the consumer to a TNE {@code CallbackManager}. */
    boolean attach(Object callbacks, Consumer<Receipt> delivery) throws ReflectiveOperationException {
        if (callbacks == null || delivery == null) return false;
        Function<Object, Boolean> consumer = this::onCallback;
        this.delivery = delivery;
        callbacks.getClass().getMethod("addConsumer", String.class, Function.class)
                .invoke(callbacks, CALLBACK, consumer);
        active = true;
        return true;
    }

    /** TNE cannot drop a consumer; this only makes it ignore further callbacks. */
    public void unregister() {
        active = false;
    }

    public boolean active() {
        return active;
    }

    /** Never throws and never cancels: TNE treats {@code true} as "stop the transaction". */
    Boolean onCallback(Object callback) {
        if (!active || callback == null) return false;
        try {
            boolean offThread = !primaryThread.getAsBoolean();
            Object result = call(callback, "result");
            if (result == null) return false;
            boolean successful = Boolean.TRUE.equals(call(result, "isSuccessful"));
            Object receipt = unwrap(call(result, "getReceipt"));
            if (receipt == null || !(call(receipt, "getId") instanceof UUID receiptId)) return false;
            Object source = call(receipt, "getSource");
            String sourceName = source == null ? "" : String.valueOf(source);
            for (String side : new String[]{"getFrom", "getTo"}) {
                Object participant = call(receipt, side);
                if (participant != null) deliver(receiptId, participant, successful, sourceName, offThread);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            warn.accept("Could not read a TNE receipt: " + e);
        }
        return false;
    }

    private void deliver(UUID receiptId, Object participant, boolean successful, String source, boolean offThread) {
        try {
            if (!(call(participant, "getId") instanceof UUID id)) return;
            BigDecimal delta = delta(call(participant, "getStartingBalances"), call(participant, "getEndingBalances"));
            Consumer<Receipt> target = delivery;
            if (target != null) target.accept(new Receipt(receiptId, id, successful, delta, source, offThread));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            warn.accept("Could not hand a TNE receipt to the server shop: " + e);
        }
    }

    /** Per-currency difference; a receipt that moved more than one currency cannot confirm a single trade. */
    static BigDecimal delta(Object starting, Object ending) throws ReflectiveOperationException {
        Map<String, BigDecimal> before = new LinkedHashMap<>();
        Map<String, BigDecimal> after = new LinkedHashMap<>();
        Map<String, UUID> currencyOf = new LinkedHashMap<>();
        if (!holdings(starting, before, currencyOf) || !holdings(ending, after, currencyOf)) return null;
        // TNE lists every holding of the account at the start (inventory, ender chest, ...) but only the
        // holdings the transaction touched at the end, so an untouched holding must not count as spent.
        Map<UUID, BigDecimal> change = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : after.entrySet()) {
            BigDecimal start = before.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            change.merge(currencyOf.get(entry.getKey()), entry.getValue().subtract(start), BigDecimal::add);
        }
        BigDecimal moved = BigDecimal.ZERO;
        int currencies = 0;
        for (BigDecimal value : change.values()) {
            if (value.signum() != 0) {
                moved = value;
                currencies++;
            }
        }
        return currencies > 1 ? null : moved;
    }

    /** Amounts keyed by currency, region and holding handler (TNE's inventory, ender chest, virtual...). */
    private static boolean holdings(Object entries, Map<String, BigDecimal> amounts, Map<String, UUID> currencyOf)
            throws ReflectiveOperationException {
        if (!(entries instanceof List<?> list)) return false;
        for (Object entry : list) {
            if (entry == null) return false;
            if (!(call(entry, "getCurrency") instanceof UUID currency)
                    || !(call(entry, "getAmount") instanceof BigDecimal amount)) return false;
            String key = currency + "|" + optional(entry, "getRegion") + "|" + optional(entry, "getHandler");
            amounts.merge(key, amount, BigDecimal::add);
            currencyOf.put(key, currency);
        }
        return true;
    }

    private static String optional(Object target, String name) throws ReflectiveOperationException {
        try {
            return String.valueOf(call(target, name));
        } catch (NoSuchMethodException e) {
            return "";
        }
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
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
