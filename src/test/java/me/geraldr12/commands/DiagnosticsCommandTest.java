package me.geraldr12.commands;

import me.geraldr12.integration.CommerceSnapshot;
import me.geraldr12.integration.IntegrationStatus;
import me.geraldr12.integration.TownSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsCommandTest {
    private static final Instant NOW = Instant.parse("2026-10-04T20:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Test
    void availableCommercePreservesMetricsAndTimeWindow() {
        CommerceSnapshot snapshot = available(2, 7, false);

        List<String> lines = DiagnosticsCommand.formatTownCommerce(snapshot);

        assertEquals(List.of(
                "commerce.status=AVAILABLE",
                "commerce.transactionCount=2",
                "commerce.quantity=7",
                "commerce.monetaryVolume=UNAVAILABLE",
                "commerce.observedAt=" + NOW,
                "commerce.window=PT5M"), lines);
    }

    @Test
    void unavailableCommerceDoesNotBecomeZero() {
        List<String> lines = DiagnosticsCommand.formatTownCommerce(
                CommerceSnapshot.unavailable(NOW, WINDOW));

        assertTrue(lines.contains("commerce.status=DATA_UNAVAILABLE"));
        assertTrue(lines.contains("commerce.transactionCount=UNAVAILABLE"));
        assertTrue(lines.contains("commerce.quantity=UNAVAILABLE"));
        assertTrue(lines.contains("commerce.monetaryVolume=UNAVAILABLE"));
    }

    @Test
    void availableZeroValuesRemainAvailable() {
        List<String> lines = DiagnosticsCommand.formatTownCommerce(available(0, 0, true));

        assertTrue(lines.contains("commerce.status=AVAILABLE"));
        assertTrue(lines.contains("commerce.transactionCount=0"));
        assertTrue(lines.contains("commerce.quantity=0"));
        assertTrue(lines.contains("commerce.monetaryVolume=0.0"));
    }

    private static CommerceSnapshot available(long count, long quantity, boolean volumeAvailable) {
        return new CommerceSnapshot(
                new CommerceSnapshot.OptionalLongValue(true, count),
                new CommerceSnapshot.OptionalLongValue(true, quantity),
                volumeAvailable
                        ? new TownSnapshot.OptionalDoubleValue(true, 0.0)
                        : TownSnapshot.OptionalDoubleValue.unavailable(),
                CommerceSnapshot.OptionalLongValue.unavailable(),
                NOW, WINDOW, IntegrationStatus.AVAILABLE);
    }
}
