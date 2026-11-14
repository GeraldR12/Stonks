package me.geraldr12.integration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownyAdapterMatrixTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void missingRequiredTownyMethodIsUnsupported() {
        assertEquals(IntegrationStatus.API_UNSUPPORTED,
                TownyAdapter.resolveStatus(new Object(), () -> {
                    throw new NoSuchMethodException("getTownBlocks");
                }));
    }

    @Test
    void townyLinkageFailureIsUnsupported() {
        assertEquals(IntegrationStatus.API_UNSUPPORTED,
                TownyAdapter.resolveStatus(new Object(), () -> {
                    throw new LinkageError("missing Towny dependency");
                }));
    }

    @Test
    void availabilityBranchesRemainExplicit() {
        assertEquals(IntegrationStatus.PLUGIN_MISSING,
                TownyAdapter.resolveStatus(null, false));
        assertEquals(IntegrationStatus.API_UNSUPPORTED,
                TownyAdapter.resolveStatus(new Object(), false));
        assertEquals(IntegrationStatus.AVAILABLE,
                TownyAdapter.resolveStatus(new Object(), true));
    }

    @Test
    void townIdentityAndCollectionsAreExtracted() {
        UUID id = UUID.randomUUID();
        TownyAdapter adapter = new TownyAdapter(new FakeApi(new FakeTown(id, "A", List.of("r"),
                List.of("b"), new FakeNation("N"), null)), IntegrationStatus.AVAILABLE,
                "0.103.2.5", Set.of(AdapterCapability.TOWN_IDENTITY));
        TownSnapshot snapshot = TownyAdapter.captureResolved(
                new FakeTown(id, "A", List.of("r"), List.of("b"), new FakeNation("N"), null), NOW);
        assertEquals(id, snapshot.townId());
        assertEquals("A", snapshot.name());
        assertEquals(1, snapshot.residentCount());
        assertEquals(1, snapshot.claimedLand());
        assertEquals("N", snapshot.nation().orElseThrow());
        assertFalse(snapshot.treasury().available());
    }

    @Test
    void missingTownReturnsNull() {
        TownyAdapter adapter = new TownyAdapter(new FakeApi(null), IntegrationStatus.AVAILABLE,
                "0.103.2.5", Set.of());
        assertNull(TownyAdapter.captureResolved(null, NOW));
    }

    @Test
    void malformedResidentCollectionFailsClosed() {
        TownyAdapter adapter = new TownyAdapter(new FakeApi(new BadTown()), IntegrationStatus.AVAILABLE,
                "0.103.2.5", Set.of());
        assertThrows(IllegalStateException.class,
                () -> TownyAdapter.captureResolved(new BadTown(), NOW));
    }

    @Test
    void nationAbsentIsUnavailable() {
        TownSnapshot snapshot = TownyAdapter.captureResolved(
                new FakeTown(UUID.randomUUID(), "A", List.of(), List.of(), null, null), NOW);
        assertTrue(snapshot.nation().isEmpty());
    }

    @Test
    void treasuryValuesRemainUnavailableOrFinite() {
        assertFalse(TownyAdapter.captureResolved(new FakeTown(UUID.randomUUID(), "A", List.of(), List.of(), null, null), NOW).treasury().available());
        assertFalse(TownyAdapter.captureResolved(new AccountTown(Double.NaN), NOW).treasury().available());
        assertFalse(TownyAdapter.captureResolved(new AccountTown(Double.POSITIVE_INFINITY), NOW).treasury().available());
        assertFalse(TownyAdapter.captureResolved(new AccountTown(-1.0), NOW).treasury().available());
        assertEquals(4.5, TownyAdapter.captureResolved(new AccountTown(4.5), NOW).treasury().value());
    }

    @Test
    void missingHoldingBalanceKeepsTreasuryUnavailable() {
        TownSnapshot snapshot = TownyAdapter.captureResolved(new MissingHoldingBalanceTown(), NOW);
        assertFalse(snapshot.treasury().available());
        assertEquals(0.0, snapshot.treasury().value());
    }

    @Test
    void malformedTownBlocksFailClosed() {
        assertThrows(IllegalStateException.class,
                () -> TownyAdapter.captureResolved(new BadBlocksTown(), NOW));
    }

    @Test
    void productionGeographicLookupUsesThreadSeam() {
        FakeTown town = new FakeTown(UUID.randomUUID(), "A", List.of(), List.of(), null, null);
        TownyAdapter adapter = new TownyAdapter(new FakeApi(town), IntegrationStatus.AVAILABLE,
                "0.103.2.5", Set.of(), () -> true);
        assertEquals(town, adapter.townAt(null));
        TownyAdapter offThread = new TownyAdapter(new FakeApi(town), IntegrationStatus.AVAILABLE,
                "0.103.2.5", Set.of(), () -> false);
        assertNull(offThread.townAt(null));
    }

    @Test
    void offMainThreadCaptureIsRejected() {
        TownyAdapter adapter = new TownyAdapter(new FakeApi(null), IntegrationStatus.AVAILABLE,
                "0.103.2.5", Set.of(), () -> false);
        assertThrows(IllegalStateException.class, () -> adapter.capture("A", NOW));
    }

    @Test
    void geographicLookupUsesTheApiResult() {
        FakeTown town = new FakeTown(UUID.randomUUID(), "A", List.of(), List.of(), null, null);
        TownyAdapter adapter = new TownyAdapter(new FakeApi(town), IntegrationStatus.AVAILABLE,
                "0.103.2.5", Set.of());
        assertEquals(town, TownyAdapter.townAtResolved(new FakeApi(town), null));
    }

    public static final class FakeApi {
        private final Object town;
        FakeApi(Object town) { this.town = town; }
        public Object getTown(String name) { return town; }
        public Object getTown(org.bukkit.Location location) { return town; }
    }

    private record FakeTown(UUID uuid, String name, List<String> residents,
                            List<String> blocks, FakeNation nation, Object account) {
        public UUID getUUID() { return uuid; }
        public List<String> getResidents() { return residents; }
        public List<String> getTownBlocks() { return blocks; }
        public FakeNation getNationOrNull() { return nation; }
        public String getName() { return name; }
    }

    private record FakeNation(String name) {
        public String getName() { return name; }
    }

    private record AccountTown(double value) {
        public UUID getUUID() { return UUID.randomUUID(); }
        public String getName() { return "account"; }
        public List<String> getResidents() { return List.of(); }
        public List<String> getTownBlocks() { return List.of(); }
        public Object getNationOrNull() { return null; }
        public Account getAccount() { return new Account(value); }
    }

    private record Account(double value) {
        public double getHoldingBalance() { return value; }
    }

    private static final class MissingHoldingBalanceTown {
        public UUID getUUID() { return UUID.randomUUID(); }
        public String getName() { return "missing-balance"; }
        public List<String> getResidents() { return List.of(); }
        public List<String> getTownBlocks() { return List.of(); }
        public Object getNationOrNull() { return null; }
        public AccountWithoutBalance getAccount() { return new AccountWithoutBalance(); }
    }

    private static final class AccountWithoutBalance {
    }

    private static final class BadBlocksTown {
        public UUID getUUID() { return UUID.randomUUID(); }
        public String getName() { return "bad-blocks"; }
        public List<String> getResidents() { return List.of(); }
        public Object getTownBlocks() { return "not-a-collection"; }
        public Object getNationOrNull() { return null; }
    }

    private static final class BadTown {
        public UUID getUUID() { return UUID.randomUUID(); }
        public String getName() { return "bad"; }
        public Object getResidents() { return "not-a-collection"; }
        public List<String> getTownBlocks() { return List.of(); }
        public Object getNationOrNull() { return null; }
    }
}
