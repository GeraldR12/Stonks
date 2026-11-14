package me.geraldr12.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase2SnapshotMatrixTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-04T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Test
    void commerceAvailableValuesRemainAvailable() {
        CommerceSnapshot snapshot = new CommerceSnapshot(
                new CommerceSnapshot.OptionalLongValue(true, 2),
                new CommerceSnapshot.OptionalLongValue(true, 7),
                new TownSnapshot.OptionalDoubleValue(true, 12.5),
                CommerceSnapshot.OptionalLongValue.unavailable(),
                OBSERVED_AT, WINDOW, IntegrationStatus.AVAILABLE);

        assertTrue(snapshot.transactionCount().available());
        assertEquals(2, snapshot.transactionCount().value());
        assertTrue(snapshot.quantity().available());
        assertEquals(7, snapshot.quantity().value());
        assertTrue(snapshot.monetaryVolume().available());
        assertEquals(12.5, snapshot.monetaryVolume().value());
        assertFalse(snapshot.shopCount().available());
    }

    @Test
    void unavailableCommerceDoesNotLookLikeAvailableZero() {
        CommerceSnapshot snapshot = CommerceSnapshot.unavailable(OBSERVED_AT, WINDOW);

        assertEquals(IntegrationStatus.DATA_UNAVAILABLE, snapshot.status());
        assertFalse(snapshot.transactionCount().available());
        assertFalse(snapshot.quantity().available());
        assertFalse(snapshot.monetaryVolume().available());
        assertFalse(snapshot.shopCount().available());
    }

    @Test
    void commerceRejectsNegativeAvailableCounts() {
        assertThrows(IllegalArgumentException.class, () -> new CommerceSnapshot(
                new CommerceSnapshot.OptionalLongValue(true, -1),
                CommerceSnapshot.OptionalLongValue.unavailable(),
                TownSnapshot.OptionalDoubleValue.unavailable(),
                CommerceSnapshot.OptionalLongValue.unavailable(),
                OBSERVED_AT, WINDOW, IntegrationStatus.AVAILABLE));
    }

    @Test
    void commerceRejectsNonzeroUnavailableCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new CommerceSnapshot.OptionalLongValue(false, 1));
    }

    @Test
    void commerceRequiresPositiveWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> CommerceSnapshot.unavailable(OBSERVED_AT, Duration.ZERO));
    }

    @Test
    void productionAvailableValuesRemainAvailable() {
        ProductionSnapshot snapshot = new ProductionSnapshot(
                new ProductionSnapshot.OptionalLongValue(true, 3),
                new TownSnapshot.OptionalDoubleValue(true, 4.5),
                OBSERVED_AT, WINDOW, IntegrationStatus.AVAILABLE);

        assertTrue(snapshot.activityCount().available());
        assertEquals(3, snapshot.activityCount().value());
        assertTrue(snapshot.earnings().available());
        assertEquals(4.5, snapshot.earnings().value());
    }

    @Test
    void productionUnavailableValuesDoNotLookLikeAvailableZero() {
        ProductionSnapshot snapshot = new ProductionSnapshot(
                ProductionSnapshot.OptionalLongValue.unavailable(),
                TownSnapshot.OptionalDoubleValue.unavailable(),
                OBSERVED_AT, WINDOW, IntegrationStatus.API_UNSUPPORTED);

        assertFalse(snapshot.activityCount().available());
        assertFalse(snapshot.earnings().available());
        assertEquals(IntegrationStatus.API_UNSUPPORTED, snapshot.status());
    }

    @Test
    void productionRejectsNegativeAvailableActivity() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionSnapshot.OptionalLongValue(true, -1));
    }

    @Test
    void productionRejectsNonzeroUnavailableActivity() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionSnapshot.OptionalLongValue(false, 1));
    }

    @Test
    void productionRejectsInvalidEarnings() {
        assertThrows(IllegalArgumentException.class,
                () -> new TownSnapshot.OptionalDoubleValue(true, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new TownSnapshot.OptionalDoubleValue(true, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new TownSnapshot.OptionalDoubleValue(true, -1.0));
    }

    @Test
    void productionRequiresPositiveWindow() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionSnapshot(
                ProductionSnapshot.OptionalLongValue.unavailable(),
                TownSnapshot.OptionalDoubleValue.unavailable(),
                OBSERVED_AT, Duration.ZERO, IntegrationStatus.DATA_UNAVAILABLE));
    }

    @Test
    void townIdentityAndPartialDataRemainDistinct() {
        UUID id = UUID.randomUUID();
        TownSnapshot snapshot = new TownSnapshot(id, "Town", Optional.empty(), 2, 4,
                TownSnapshot.OptionalDoubleValue.unavailable(), OBSERVED_AT,
                IntegrationStatus.AVAILABLE);

        assertEquals(id, snapshot.townId());
        assertEquals("Town", snapshot.name());
        assertEquals(2, snapshot.residentCount());
        assertEquals(4, snapshot.claimedLand());
        assertTrue(snapshot.nation().isEmpty());
        assertFalse(snapshot.treasury().available());
    }

    @Test
    void townRejectsNegativeLand() {
        assertThrows(IllegalArgumentException.class, () -> new TownSnapshot(
                UUID.randomUUID(), "Town", Optional.empty(), 1, -1,
                TownSnapshot.OptionalDoubleValue.unavailable(), OBSERVED_AT,
                IntegrationStatus.AVAILABLE));
    }
}
