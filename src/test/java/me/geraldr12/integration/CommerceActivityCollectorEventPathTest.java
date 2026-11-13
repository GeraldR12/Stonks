package me.geraldr12.integration;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceActivityCollectorEventPathTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void executorAcceptsOnlyExactExpectedEventClass() throws Exception {
        UUID townId = UUID.randomUUID();
        org.bukkit.Location location = new org.bukkit.Location(null, 0, 0, 0);
        CommerceActivityCollector collector = new CommerceActivityCollector(
                null, null, () -> true, ignored -> new LocationTown(townId));
        EventExecutor executor = collector.createExecutor(SuccessEvent.class);

        executor.execute(collector, new ParentEvent(new Shop(location), 1));
        assertFalse(collector.snapshot(townId, NOW, Duration.ofMinutes(5)).transactionCount().available());

        executor.execute(collector, new SuccessEvent(new Shop(location), 1));
        CommerceSnapshot snapshot = collector.snapshot(townId, NOW, Duration.ofMinutes(5));
        assertEquals(1, snapshot.transactionCount().value());
        assertEquals(1, snapshot.quantity().value());
    }

    @Test
    void parentThenSuccessProducesOneObservation() throws Exception {
        UUID townId = UUID.randomUUID();
        org.bukkit.Location location = new org.bukkit.Location(null, 0, 0, 0);
        CommerceActivityCollector collector = new CommerceActivityCollector(
                null, null, () -> true, ignored -> new LocationTown(townId));
        EventExecutor executor = collector.createExecutor(SuccessEvent.class);

        executor.execute(collector, new ParentEvent(new Shop(location), 1));
        executor.execute(collector, new SuccessEvent(new Shop(location), 1));

        CommerceSnapshot snapshot = collector.snapshot(townId, NOW, Duration.ofMinutes(5));
        assertEquals(1, snapshot.transactionCount().value());
        assertEquals(1, snapshot.quantity().value());
    }

    @Test
    void twoDistinctSuccessEventsRemainTwoObservations() throws Exception {
        UUID townId = UUID.randomUUID();
        org.bukkit.Location location = new org.bukkit.Location(null, 0, 0, 0);
        CommerceActivityCollector collector = new CommerceActivityCollector(
                null, null, () -> true, ignored -> new LocationTown(townId));
        EventExecutor executor = collector.createExecutor(SuccessEvent.class);

        executor.execute(collector, new SuccessEvent(new Shop(location), 1));
        executor.execute(collector, new SuccessEvent(new Shop(location), 1));

        CommerceSnapshot snapshot = collector.snapshot(townId, NOW, Duration.ofMinutes(5));
        assertEquals(2, snapshot.transactionCount().value());
        assertEquals(2, snapshot.quantity().value());
    }

    @Test
    void eventPathUsesResolvedShopTownOnly() throws Exception {
        UUID townA = UUID.randomUUID();
        LocationTown resolvedTown = new LocationTown(townA);
        org.bukkit.Location location = new org.bukkit.Location(null, 0, 0, 0);
        CommerceActivityCollector collector = new CommerceActivityCollector(
                null, null, () -> true, ignored -> resolvedTown);
        collector.observeEvent(new PurchaseEvent(new Shop(location), 4, UUID.randomUUID(), UUID.randomUUID()));

        CommerceSnapshot snapshot = collector.snapshot(townA, NOW, Duration.ofMinutes(5));
        assertEquals(1, snapshot.transactionCount().value());
        assertEquals(4, snapshot.quantity().value());
        assertFalse(snapshot.monetaryVolume().available());
    }

    @Test
    void townBAndOutsideTownRemainIndependent() throws Exception {
        UUID townA = UUID.randomUUID();
        UUID townB = UUID.randomUUID();
        org.bukkit.Location location = new org.bukkit.Location(null, 0, 0, 0);
        AtomicReference<LocationTown> town = new AtomicReference<>(new LocationTown(townA));
        CommerceActivityCollector collector = new CommerceActivityCollector(
                null, null, () -> true,
                ignored -> town.get());

        collector.observeEvent(new PurchaseEvent(new Shop(location), 3, null, null));
        town.set(new LocationTown(townB));
        collector.observeEvent(new PurchaseEvent(new Shop(location), 5, null, null));
        town.set(null);
        collector.observeEvent(new PurchaseEvent(new Shop(location), 7, null, null));

        assertEquals(3, collector.snapshot(townA, NOW, Duration.ofMinutes(5)).quantity().value());
        assertEquals(5, collector.snapshot(townB, NOW, Duration.ofMinutes(5)).quantity().value());
        assertFalse(collector.snapshot(UUID.randomUUID(), NOW, Duration.ofMinutes(5))
                .transactionCount().available());
    }

    @Test
    void passiveConstructionAndRepeatedCleanupRemainInactive() {
        CommerceActivityCollector collector = new CommerceActivityCollector(
                null, null, () -> true, ignored -> null);

        assertFalse(collector.isRegistered());
        collector.unregister();
        collector.unregister();
        assertFalse(collector.isRegistered());
    }

    @Test
    void registrationSuccessDuplicateAndCleanupAreDeterministic() {
        AtomicReference<Integer> registrations = new AtomicReference<>(0);
        CommerceActivityCollector collector = new CommerceActivityCollector(
                null, null, () -> true, ignored -> null,
                (plugin, registeredCollector) -> registrations.updateAndGet(value -> value + 1));

        collector.register(null);
        collector.register(null);
        assertEquals(1, registrations.get());
        assertTrue(collector.isRegistered());

        collector.unregister();
        collector.unregister();
        assertFalse(collector.isRegistered());
    }

    @Test
    void runtimeRegistrationFailureCleansAndPropagates() {
        AtomicReference<Integer> cleanups = new AtomicReference<>(0);
        CommerceActivityCollector collector = new CommerceActivityCollector(
                null, null, () -> true, ignored -> null,
                (plugin, registeredCollector) -> {
                    HandlerList.unregisterAll(registeredCollector);
                    cleanups.updateAndGet(value -> value + 1);
                    throw new IllegalStateException("registration failed");
                });

        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () -> collector.register(null));
        } finally {
            collector.unregister();
        }
        assertFalse(collector.isRegistered());
        assertEquals(1, cleanups.get());
    }

    private static class ParentEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Shop shop;
        private final int amount;

        ParentEvent(Shop shop, int amount) {
            this.shop = shop;
            this.amount = amount;
        }

        public Shop getShop() { return shop; }
        public int getAmount() { return amount; }
        public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    private static final class SuccessEvent extends ParentEvent {
        SuccessEvent(Shop shop, int amount) { super(shop, amount); }
    }

    private record PurchaseEvent(Shop shop, int amount, UUID purchaser, UUID owner) {
        public Shop getShop() { return shop; }
        public int getAmount() { return amount; }
    }

    private record Shop(org.bukkit.Location location) {
        public org.bukkit.Location getLocation() { return location; }
    }

    private record LocationTown(UUID townId) {
        public UUID getUUID() { return townId; }
    }
}
