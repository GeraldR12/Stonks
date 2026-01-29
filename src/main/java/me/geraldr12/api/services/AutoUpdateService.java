package me.geraldr12.api.services;

import me.geraldr12.Stonks;
import me.geraldr12.api.endpoints.implementation.PluginReleaseAPIEndpoint;
import me.geraldr12.api.entities.PluginReleaseEntity;
import lombok.extern.log4j.Log4j2;
import org.apache.maven.artifact.versioning.ComparableVersion;

import javax.inject.Inject;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Log4j2
public class AutoUpdateService implements ApiService {

    private final Stonks plugin;

    @Inject
    public AutoUpdateService(Stonks plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<PluginReleaseEntity> getLatestRelease() {

        PluginReleaseAPIEndpoint pluginReleaseAPIEndpoint = new PluginReleaseAPIEndpoint();

        return CompletableFuture.supplyAsync(() -> {

            PluginReleaseEntity pluginReleaseEntity;

            try {
                pluginReleaseEntity = (PluginReleaseEntity) pluginReleaseAPIEndpoint
                        .executeRequest()
                        .parseData();
            } catch (IOException e) {
                log.warn("Error while parsing data from API endpoint: " + PluginReleaseAPIEndpoint.class.getName(), e);
                return null;
            }

            return pluginReleaseEntity;

        });

    }

    public String getCurrentVersion() {
        return plugin.getDescription().getVersion();
    }

    public CompletableFuture<Boolean> isUpdateAvailable() {

        return CompletableFuture.supplyAsync(() -> {

            PluginReleaseEntity latestRelease = getLatestRelease().join();

            if (latestRelease == null) {
                return false;
            }

            String latestReleaseVersion = latestRelease.getReleaseVersion().replace("v", "");

            ComparableVersion latestVersion = new ComparableVersion(latestReleaseVersion);
            ComparableVersion currentVersion = new ComparableVersion(plugin.getDescription().getVersion());

            return currentVersion.compareTo(latestVersion) < 0;

        });

    }

}
