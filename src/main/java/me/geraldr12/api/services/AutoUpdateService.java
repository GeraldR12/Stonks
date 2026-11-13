package me.geraldr12.api.services;

import me.geraldr12.Stonks;
import me.geraldr12.api.entities.PluginReleaseEntity;
import lombok.extern.log4j.Log4j2;
import org.apache.maven.artifact.versioning.ComparableVersion;

import javax.inject.Inject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
public class AutoUpdateService implements ApiService {

    private final Stonks plugin;
    private final String currentVersion;
    private final Set<CompletableFuture<?>> pendingRequests = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean skipLogged = new AtomicBoolean();
    private volatile boolean shuttingDown;

    @Inject
    public AutoUpdateService(Stonks plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    public CompletableFuture<PluginReleaseEntity> getLatestRelease() {
        if (shuttingDown) {
            return CompletableFuture.completedFuture(null);
        }

        if (skipLogged.compareAndSet(false, true)) {
            log.info("Stonks has no release identity; skipping external update check.");
        }
        return CompletableFuture.completedFuture(null);
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public CompletableFuture<Boolean> isUpdateAvailable() {
        String currentVersionSnapshot = currentVersion;
        return getLatestRelease().thenApply(latestRelease -> {
            if (latestRelease == null || latestRelease.getReleaseVersion() == null) {
                return false;
            }
            String latestReleaseVersion = latestRelease.getReleaseVersion().replace("v", "");
            ComparableVersion latestVersion = new ComparableVersion(latestReleaseVersion);
            ComparableVersion currentVersion = new ComparableVersion(currentVersionSnapshot);
            return currentVersion.compareTo(latestVersion) < 0;
        }).exceptionally(error -> {
            log.warn("Unable to determine whether a Stonks update is available.", error);
            return false;
        });
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public void shutdown() {
        shuttingDown = true;
        pendingRequests.forEach(request -> request.cancel(true));
        pendingRequests.clear();
    }

    private void track(CompletableFuture<?> future) {
        pendingRequests.add(future);
        future.whenComplete((ignored, error) -> pendingRequests.remove(future));
    }

}
