package me.geraldr12.integration;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceObservationNormalizationTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void validIntegralPurchaseIsNormalized() {
        CommerceObservation observation = CommerceActivityCollector.normalize(3L, 12.5, OBSERVED_AT);

        assertEquals(3, observation.quantity());
        assertEquals(12.5, observation.volume());
        assertTrue(observation.volumeAvailable());
        assertEquals(OBSERVED_AT, observation.observedAt());
    }

    @Test
    void invalidQuantitiesAreRejectedBeforeMutation() {
        assertNull(CommerceActivityCollector.normalize(null, 1.0, OBSERVED_AT));
        assertNull(CommerceActivityCollector.normalize(-1L, 1.0, OBSERVED_AT));
        assertNull(CommerceActivityCollector.normalize(1.5, 1.0, OBSERVED_AT));
        assertNull(CommerceActivityCollector.normalize(Double.NaN, 1.0, OBSERVED_AT));
        assertNull(CommerceActivityCollector.normalize(Double.POSITIVE_INFINITY, 1.0, OBSERVED_AT));
    }

    @Test
    void invalidMonetaryValueKeepsObservationButMarksVolumeUnavailable() {
        CommerceObservation nullValue = CommerceActivityCollector.normalize(2L, null, OBSERVED_AT);
        CommerceObservation nanValue = CommerceActivityCollector.normalize(2L, Double.NaN, OBSERVED_AT);
        CommerceObservation infiniteValue = CommerceActivityCollector.normalize(2L, Double.POSITIVE_INFINITY, OBSERVED_AT);
        CommerceObservation negativeValue = CommerceActivityCollector.normalize(2L, -1.0, OBSERVED_AT);

        assertFalse(nullValue.volumeAvailable());
        assertFalse(nanValue.volumeAvailable());
        assertFalse(infiniteValue.volumeAvailable());
        assertFalse(negativeValue.volumeAvailable());
        assertEquals(2, nullValue.quantity());
    }
}
