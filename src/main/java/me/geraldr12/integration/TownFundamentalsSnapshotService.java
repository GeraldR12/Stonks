package me.geraldr12.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

public final class TownFundamentalsSnapshotService {
    private final TownyAdapter towny;
    private final CommerceActivityCollector commerce;
    private final AdvancedJobsAdapter jobs;
    private final BooleanSupplier primaryThread;

    public TownFundamentalsSnapshotService(TownyAdapter towny,
                                           CommerceActivityCollector commerce,
                                           AdvancedJobsAdapter jobs) {
        this(towny, commerce, jobs, org.bukkit.Bukkit::isPrimaryThread);
    }

    TownFundamentalsSnapshotService(TownyAdapter towny, CommerceActivityCollector commerce,
                                    AdvancedJobsAdapter jobs, BooleanSupplier primaryThread) {
        this.towny = towny;
        this.commerce = commerce;
        this.jobs = jobs;
        this.primaryThread = primaryThread;
    }

    public TownSnapshot captureOnMainThread(String townName, Instant observedAt) {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Town snapshots must be captured on the main thread");
        }
        if (towny.status() != IntegrationStatus.AVAILABLE) return null;
        return towny.capture(townName, observedAt);
    }

    public CommerceSnapshot commerce(String townName, Instant observedAt, Duration window) {
        TownSnapshot town = captureOnMainThread(townName, observedAt);
        if (town == null) return CommerceSnapshot.unavailable(observedAt, window);
        return commerce.snapshot(town.townId(), observedAt, window);
    }

    public ProductionSnapshot production(Instant observedAt, Duration window) {
        IntegrationStatus status = jobs.status() == IntegrationStatus.AVAILABLE
                ? IntegrationStatus.DATA_UNAVAILABLE : jobs.status();
        return new ProductionSnapshot(
                ProductionSnapshot.OptionalLongValue.unavailable(),
                TownSnapshot.OptionalDoubleValue.unavailable(),
                observedAt, window, status);
    }
}
