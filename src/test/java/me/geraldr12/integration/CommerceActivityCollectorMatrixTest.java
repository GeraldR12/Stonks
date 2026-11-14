package me.geraldr12.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceActivityCollectorMatrixTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void deliveredEventsAreCountedIndividuallyWithoutHeuristicDeduplication() {
        Deque<CommerceObservation> observations = new ArrayDeque<>();
        observations.add(new CommerceObservation(NOW, 1, 0.0, false));
        observations.add(new CommerceObservation(NOW, 1, 0.0, false));

        CommerceSnapshot snapshot = CommerceActivityCollector.aggregate(observations, NOW, Duration.ofMinutes(5));
        assertEquals(2, snapshot.transactionCount().value());
        assertEquals(2, snapshot.quantity().value());
        assertFalse(snapshot.monetaryVolume().available());
    }

    @Test
    void exactCutoffIsIncludedAndOlderObservationExpires() {
        Deque<CommerceObservation> observations = new ArrayDeque<>();
        observations.add(new CommerceObservation(NOW.minusSeconds(301), 1, 0.0, false));
        observations.add(new CommerceObservation(NOW.minusSeconds(300), 2, 0.0, false));
        CommerceActivityCollector.trim(observations, NOW, Duration.ofMinutes(5));
        assertEquals(1, observations.size());
        assertEquals(2, observations.peekFirst().quantity());
    }

    @Test
    void resolvedObservationPathKeepsTownQueuesIndependent() {
        CommerceActivityCollector collector = new CommerceActivityCollector(null, null, () -> true);
        UUID townA = UUID.randomUUID();
        UUID townB = UUID.randomUUID();
        collector.observeResolved(townA, 3, NOW);
        collector.observeResolved(townB, 5, NOW);
        assertEquals(3, collector.snapshot(townA, NOW, Duration.ofMinutes(5)).quantity().value());
        assertEquals(5, collector.snapshot(townB, NOW, Duration.ofMinutes(5)).quantity().value());
    }

    @Test
    void malformedQuantityDoesNotAppend() {
        CommerceActivityCollector collector = new CommerceActivityCollector(null, null, () -> true);
        UUID town = UUID.randomUUID();
        collector.observeResolved(town, 1.5, NOW);
        assertFalse(collector.snapshot(town, NOW, Duration.ofMinutes(5)).transactionCount().available());
    }

    @Test
    void quantityOverflowFailsClosed() {
        Deque<CommerceObservation> observations = new ArrayDeque<>();
        observations.add(new CommerceObservation(NOW, Long.MAX_VALUE, 0.0, false));
        observations.add(new CommerceObservation(NOW, 1, 0.0, false));
        CommerceSnapshot snapshot = CommerceActivityCollector.aggregate(observations, NOW, Duration.ofMinutes(5));
        assertEquals(IntegrationStatus.DATA_UNAVAILABLE, snapshot.status());
        assertFalse(snapshot.quantity().available());
    }

    @Test
    void boundedAppendRemovesOldestAndKeepsNewest() {
        Deque<CommerceObservation> observations = new ArrayDeque<>();
        for (int i = 0; i < CommerceActivityCollector.MAX_OBSERVATIONS + 1; i++) {
            CommerceActivityCollector.appendAndPrune(observations,
                    new CommerceObservation(NOW, i, 0.0, false), NOW);
        }
        assertEquals(CommerceActivityCollector.MAX_OBSERVATIONS, observations.size());
        assertEquals(1, observations.peekFirst().quantity());
        assertEquals(CommerceActivityCollector.MAX_OBSERVATIONS, observations.peekLast().quantity());
    }

    @Test
    void boundedRetentionConstantIsExplicit() {
        assertTrue(CommerceActivityCollector.MAX_OBSERVATIONS > 0);
    }
}
