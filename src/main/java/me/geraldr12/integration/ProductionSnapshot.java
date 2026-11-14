package me.geraldr12.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record ProductionSnapshot(
        OptionalLongValue activityCount,
        TownSnapshot.OptionalDoubleValue earnings,
        Instant observedAt,
        Duration window,
        IntegrationStatus status) {
    public ProductionSnapshot {
        Objects.requireNonNull(activityCount, "activityCount");
        Objects.requireNonNull(earnings, "earnings");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(window, "window");
        if (window.isNegative() || window.isZero()) throw new IllegalArgumentException("window");
        Objects.requireNonNull(status, "status");
    }

    public record OptionalLongValue(boolean available, long value) {
        public OptionalLongValue {
            if (available && value < 0) throw new IllegalArgumentException("value");
            if (!available && value != 0) throw new IllegalArgumentException("unavailable value");
        }

        public static OptionalLongValue unavailable() {
            return new OptionalLongValue(false, 0);
        }
    }
}
