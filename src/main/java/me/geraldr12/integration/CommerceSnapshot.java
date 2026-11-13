package me.geraldr12.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record CommerceSnapshot(
        OptionalLongValue transactionCount,
        OptionalLongValue quantity,
        TownSnapshot.OptionalDoubleValue monetaryVolume,
        OptionalLongValue shopCount,
        Instant observedAt,
        Duration window,
        IntegrationStatus status) {
    public CommerceSnapshot {
        Objects.requireNonNull(transactionCount, "transactionCount");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(monetaryVolume, "monetaryVolume");
        Objects.requireNonNull(shopCount, "shopCount");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(window, "window");
        if (window.isNegative() || window.isZero()) throw new IllegalArgumentException("window");
        Objects.requireNonNull(status, "status");
    }

    public static CommerceSnapshot unavailable(Instant observedAt, Duration window) {
        return new CommerceSnapshot(OptionalLongValue.unavailable(), OptionalLongValue.unavailable(),
                TownSnapshot.OptionalDoubleValue.unavailable(), OptionalLongValue.unavailable(),
                observedAt, window, IntegrationStatus.DATA_UNAVAILABLE);
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
