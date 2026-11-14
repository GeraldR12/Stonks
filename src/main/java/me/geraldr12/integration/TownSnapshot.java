package me.geraldr12.integration;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TownSnapshot(
        UUID townId,
        String name,
        Optional<String> nation,
        int residentCount,
        int claimedLand,
        OptionalDoubleValue treasury,
        Instant observedAt,
        IntegrationStatus status) {
    public TownSnapshot {
        Objects.requireNonNull(townId, "townId");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        nation = Objects.requireNonNull(nation, "nation");
        if (residentCount < 0) throw new IllegalArgumentException("residentCount");
        if (claimedLand < 0) throw new IllegalArgumentException("claimedLand");
        treasury = Objects.requireNonNull(treasury, "treasury");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        status = Objects.requireNonNull(status, "status");
    }

    public record OptionalDoubleValue(boolean available, double value) {
        public OptionalDoubleValue {
            if (available && (!Double.isFinite(value) || value < 0.0)) {
                throw new IllegalArgumentException("value");
            }
            if (!available && value != 0.0) throw new IllegalArgumentException("unavailable value");
        }

        public static OptionalDoubleValue unavailable() {
            return new OptionalDoubleValue(false, 0.0);
        }
    }
}
