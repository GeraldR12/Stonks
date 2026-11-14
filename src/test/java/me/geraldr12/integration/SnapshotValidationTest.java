package me.geraldr12.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotValidationTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void townSnapshotRejectsInvalidCounts() {
        assertThrows(IllegalArgumentException.class, () -> new TownSnapshot(
                UUID.randomUUID(), "Town", Optional.empty(), -1, 0,
                TownSnapshot.OptionalDoubleValue.unavailable(), OBSERVED_AT,
                IntegrationStatus.AVAILABLE));
    }

    @Test
    void townSnapshotRejectsNonFiniteTreasury() {
        assertThrows(IllegalArgumentException.class, () -> new TownSnapshot(
                UUID.randomUUID(), "Town", Optional.empty(), 1, 2,
                new TownSnapshot.OptionalDoubleValue(true, Double.NaN), OBSERVED_AT,
                IntegrationStatus.AVAILABLE));
    }

    @Test
    void unavailableTreasuryIsNotZeroData() {
        TownSnapshot snapshot = new TownSnapshot(
                UUID.randomUUID(), "Town", Optional.empty(), 1, 2,
                TownSnapshot.OptionalDoubleValue.unavailable(), OBSERVED_AT,
                IntegrationStatus.AVAILABLE);

        assertEquals(false, snapshot.treasury().available());
    }

    @Test
    void commerceSnapshotRejectsNonFiniteVolume() {
        assertThrows(IllegalArgumentException.class, () -> new CommerceSnapshot(
                new CommerceSnapshot.OptionalLongValue(true, 1),
                new CommerceSnapshot.OptionalLongValue(true, 1),
                new TownSnapshot.OptionalDoubleValue(true, Double.POSITIVE_INFINITY),
                new CommerceSnapshot.OptionalLongValue(true, 1), OBSERVED_AT,
                Duration.ofMinutes(5), IntegrationStatus.AVAILABLE));
    }

    @Test
    void productionSnapshotRequiresPositiveWindow() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionSnapshot(
                new ProductionSnapshot.OptionalLongValue(true, 1),
                new TownSnapshot.OptionalDoubleValue(true, 2.0),
                OBSERVED_AT, Duration.ZERO, IntegrationStatus.DATA_UNAVAILABLE));
    }
}
