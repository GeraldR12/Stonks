package me.geraldr12.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownFundamentalsSnapshotServiceMatrixTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Test
    void unsupportedTownyReturnsNullAtServiceBoundary() {
        TownyAdapter towny = new TownyAdapter(null, IntegrationStatus.PLUGIN_MISSING, "unknown", Set.of());
        TownFundamentalsSnapshotService service = new TownFundamentalsSnapshotService(
                towny, null, new AdvancedJobsAdapter(null), () -> true);
        assertNull(service.captureOnMainThread("missing", NOW));
    }

    @Test
    void unsupportedJobsRemainUnavailable() {
        TownFundamentalsSnapshotService service = new TownFundamentalsSnapshotService(
                new TownyAdapter(null, IntegrationStatus.PLUGIN_MISSING, "unknown", Set.of()),
                null, new AdvancedJobsAdapter(null));
        ProductionSnapshot snapshot = service.production(NOW, WINDOW);
        assertEquals(IntegrationStatus.PLUGIN_MISSING, snapshot.status());
        assertFalse(snapshot.activityCount().available());
        assertFalse(snapshot.earnings().available());
    }

    @Test
    void offMainThreadCaptureIsRejectedBeforeTownyAccess() {
        TownFundamentalsSnapshotService service = new TownFundamentalsSnapshotService(
                null, null, new AdvancedJobsAdapter(null), () -> false);
        assertThrows(IllegalStateException.class, () -> service.captureOnMainThread("A", NOW));
    }

    @Test
    void commerceUnavailableTownPreservesWindowAndStatus() {
        TownFundamentalsSnapshotService service = new TownFundamentalsSnapshotService(
                new TownyAdapter(null, IntegrationStatus.PLUGIN_MISSING, "unknown", Set.of()),
                null, new AdvancedJobsAdapter(null), () -> true);
        CommerceSnapshot snapshot = service.commerce("missing", NOW, WINDOW);
        assertEquals(NOW, snapshot.observedAt());
        assertEquals(WINDOW, snapshot.window());
        assertFalse(snapshot.transactionCount().available());
        assertEquals(IntegrationStatus.DATA_UNAVAILABLE, snapshot.status());
    }

    @Test
    void commerceDelegatesUsingCapturedTownIdAndPreservesObservation() {
        UUID townId = UUID.randomUUID();
        TownFundamentalsSnapshotService service = serviceFor(townId);
        CommerceActivityCollector collector = new CommerceActivityCollector(null, null, () -> true);
        collector.observeResolved(townId, 6, NOW);
        service = new TownFundamentalsSnapshotService(
                towny(townId), collector, new AdvancedJobsAdapter(null), () -> true);

        CommerceSnapshot snapshot = service.commerce("A", NOW, WINDOW);

        assertEquals(1, snapshot.transactionCount().value());
        assertEquals(6, snapshot.quantity().value());
        assertFalse(snapshot.monetaryVolume().available());
        assertEquals(NOW, snapshot.observedAt());
        assertEquals(WINDOW, snapshot.window());
    }

    @Test
    void partialSnapshotValuesPreserveAvailability() {
        TownSnapshot town = new TownSnapshot(UUID.randomUUID(), "A", Optional.empty(), 1, 2,
                TownSnapshot.OptionalDoubleValue.unavailable(), NOW, IntegrationStatus.AVAILABLE);
        assertEquals(IntegrationStatus.AVAILABLE, town.status());
        assertFalse(town.treasury().available());
    }

    private static TownFundamentalsSnapshotService serviceFor(UUID townId) {
        return new TownFundamentalsSnapshotService(towny(townId),
                new CommerceActivityCollector(null, null, () -> true),
                new AdvancedJobsAdapter(null), () -> true);
    }

    private static TownyAdapter towny(UUID townId) {
        return new TownyAdapter(new Api(new Town(townId)), IntegrationStatus.AVAILABLE,
                "0.103.2.5", Set.of(), () -> true);
    }

    public static final class Api {
        private final Town town;
        Api(Town town) { this.town = town; }
        public Town getTown(String name) { return town; }
        public Town getTown(org.bukkit.Location location) { return town; }
    }

    public static final class Town {
        private final UUID id;
        Town(UUID id) { this.id = id; }
        public UUID getUUID() { return id; }
        public String getName() { return "A"; }
        public java.util.List<String> getResidents() { return java.util.List.of(); }
        public java.util.List<String> getTownBlocks() { return java.util.List.of(); }
        public Object getNationOrNull() { return null; }
    }
}
