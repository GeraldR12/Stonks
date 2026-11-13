package me.geraldr12.integration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CommerceActivityCollector implements Listener {
    private static final Duration MAX_RETENTION = Duration.ofHours(1);
    private static final int MAX_OBSERVATIONS_PER_TOWN = 10_000;

    private final QuickShopAdapter quickShop;
    private final TownyAdapter towny;
    private final Map<UUID, Deque<CommerceObservation>> byTown = new HashMap<>();
    private final java.util.function.BooleanSupplier primaryThread;
    private final java.util.function.Function<Location, Object> townResolver;
    private final Registration registration;
    private final boolean optionalIntegration;
    private boolean registered;

    @FunctionalInterface
    interface Registration {
        void register(Plugin plugin, CommerceActivityCollector collector)
                throws ReflectiveOperationException;
    }

    public CommerceActivityCollector(QuickShopAdapter quickShop, TownyAdapter towny) {
        this(quickShop, towny, Bukkit::isPrimaryThread,
                towny == null ? ignored -> null : towny::townAt,
                CommerceActivityCollector::registerWithBukkit, true);
    }

    CommerceActivityCollector(QuickShopAdapter quickShop, TownyAdapter towny,
                              java.util.function.BooleanSupplier primaryThread) {
        this(quickShop, towny, primaryThread,
                towny == null ? ignored -> null : towny::townAt);
    }

    CommerceActivityCollector(QuickShopAdapter quickShop, TownyAdapter towny,
                              java.util.function.BooleanSupplier primaryThread,
                              java.util.function.Function<Location, Object> townResolver) {
        this(quickShop, towny, primaryThread, townResolver,
                CommerceActivityCollector::registerWithBukkit, true);
    }

    CommerceActivityCollector(QuickShopAdapter quickShop, TownyAdapter towny,
                              java.util.function.BooleanSupplier primaryThread,
                              java.util.function.Function<Location, Object> townResolver,
                              Registration registration) {
        this(quickShop, towny, primaryThread, townResolver, registration, false);
    }

    private CommerceActivityCollector(QuickShopAdapter quickShop, TownyAdapter towny,
                                      java.util.function.BooleanSupplier primaryThread,
                                      java.util.function.Function<Location, Object> townResolver,
                                      Registration registration,
                                      boolean optionalIntegration) {
        this.quickShop = quickShop;
        this.towny = towny;
        this.primaryThread = primaryThread;
        this.townResolver = townResolver;
        this.registration = registration;
        this.optionalIntegration = optionalIntegration;
    }

    static final int MAX_OBSERVATIONS = MAX_OBSERVATIONS_PER_TOWN;

    public synchronized void register(Plugin plugin) {
        if (registered || (optionalIntegration
                && quickShop.status() != IntegrationStatus.AVAILABLE)) return;

        try {
            registration.register(plugin, this);
            registered = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            registered = false;
            HandlerList.unregisterAll(this);
        } catch (RuntimeException exception) {
            registered = false;
            HandlerList.unregisterAll(this);
            throw exception;
        }
    }

    private static void registerWithBukkit(Plugin plugin,
                                           CommerceActivityCollector collector)
            throws ReflectiveOperationException {
        ClassLoader loader = collector.quickShop.classLoader();
        Class<? extends Event> eventType = Class.forName(
                "com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent",
                true,
                loader).asSubclass(Event.class);
        EventExecutor executor = collector.createExecutor(eventType);
        Bukkit.getPluginManager().registerEvent(
                eventType,
                collector,
                EventPriority.MONITOR,
                executor,
                plugin);
    }

    public synchronized void unregister() {
        HandlerList.unregisterAll(this);
        registered = false;
    }

    synchronized boolean isRegistered() {
        return registered;
    }

    EventExecutor createExecutor(Class<? extends Event> expectedEventType) {
        return (listener, event) -> {
            if (event.getClass() != expectedEventType) return;
            observe(event);
        };
    }

    private void observe(Event event) {
        if (!primaryThread.getAsBoolean()) return;
        try {
            observeEvent(event);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    void observeEvent(Object event) throws ReflectiveOperationException {
            Object shop = event.getClass().getMethod("getShop").invoke(event);
            Location location = (Location) shop.getClass().getMethod("getLocation").invoke(shop);
            Object town = townResolver.apply(location);
            if (town == null) return;
            UUID townId = (UUID) town.getClass().getMethod("getUUID").invoke(town);
            Number amount = (Number) event.getClass().getMethod("getAmount").invoke(event);
            observeResolved(townId, amount, Instant.now());
    }

    static CommerceObservation normalize(Number amount, Number balance, Instant observedAt) {
        if (amount == null || amount.doubleValue() < 0
                || !Double.isFinite(amount.doubleValue())
                || amount.longValue() != amount.doubleValue()) return null;
        boolean volumeAvailable = balance != null
                && Double.isFinite(balance.doubleValue())
                && balance.doubleValue() >= 0;
        return new CommerceObservation(observedAt, amount.longValue(),
                volumeAvailable ? balance.doubleValue() : 0.0,
                volumeAvailable);
    }

    void observeResolved(UUID townId, Number quantity, Instant observedAt) {
        CommerceObservation observation = normalize(quantity, null, observedAt);
        if (observation == null) return;
        Deque<CommerceObservation> observations = byTown.computeIfAbsent(
                townId, ignored -> new ArrayDeque<>());
        appendAndPrune(observations, observation, observedAt);
    }

    static void appendAndPrune(Deque<CommerceObservation> observations,
                               CommerceObservation observation, Instant now) {
        observations.addLast(observation);
        while (observations.size() > MAX_OBSERVATIONS_PER_TOWN) observations.removeFirst();
        Instant cutoff = now.minus(MAX_RETENTION);
        while (!observations.isEmpty()
                && observations.peekFirst().observedAt().isBefore(cutoff)) {
            observations.removeFirst();
        }
    }

    public CommerceSnapshot snapshot(UUID townId, Instant observedAt, Duration window) {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Commerce snapshots must be captured on the main thread");
        }
        Deque<CommerceObservation> observations = byTown.get(townId);
        if (observations == null) return CommerceSnapshot.unavailable(observedAt, window);
        trim(observations, observedAt, window);
        return aggregate(observations, observedAt, window);
    }

    static void trim(Deque<CommerceObservation> observations, Instant observedAt, Duration window) {
        Instant cutoff = observedAt.minus(window);
        while (!observations.isEmpty() && observations.peekFirst().observedAt().isBefore(cutoff)) {
            observations.removeFirst();
        }
    }

    static CommerceSnapshot aggregate(Deque<CommerceObservation> observations,
                                      Instant observedAt, Duration window) {
        if (observations.isEmpty()) return CommerceSnapshot.unavailable(observedAt, window);
        long quantity = 0;
        double volume = 0.0;
        boolean volumeAvailable = true;
        try {
            for (CommerceObservation observation : observations) {
                quantity = Math.addExact(quantity, observation.quantity());
                volume += observation.volume();
                volumeAvailable &= observation.volumeAvailable();
            }
        } catch (ArithmeticException e) {
            return CommerceSnapshot.unavailable(observedAt, window);
        }
        if (!Double.isFinite(volume)) return CommerceSnapshot.unavailable(observedAt, window);
        return new CommerceSnapshot(
                new CommerceSnapshot.OptionalLongValue(true, observations.size()),
                new CommerceSnapshot.OptionalLongValue(true, quantity),
                volumeAvailable
                        ? new TownSnapshot.OptionalDoubleValue(true, volume)
                        : TownSnapshot.OptionalDoubleValue.unavailable(),
                CommerceSnapshot.OptionalLongValue.unavailable(),
                observedAt, window, IntegrationStatus.AVAILABLE);
    }
}
