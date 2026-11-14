package me.geraldr12;

import com.google.inject.name.Named;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.v110.V110MigrationCoordinator;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;

import javax.inject.Inject;
import java.io.File;
import java.util.logging.Logger;

public class MigrationHandler {

    public FileConfiguration pluginConfiguration;
    public PluginDescriptionFile pluginDescriptionFile;

    public File pluginDataDirectory;
    public Logger pluginLogger;
    public CompaniesService companiesService;
    public PlayersService playersService;
    public SignsService signsService;

    @Inject
    public MigrationHandler(@Named("pluginConfig") FileConfiguration pluginConfiguration, PluginDescriptionFile pluginDescriptionFile,
                            @Named("pluginDataDirectory") File pluginDataDirectory, @Named("bukkitLogger") Logger pluginLogger,
                            CompaniesService companiesService, PlayersService playersService, SignsService signsService) {

        this.pluginConfiguration = pluginConfiguration;
        this.pluginDescriptionFile = pluginDescriptionFile;

        this.pluginDataDirectory = pluginDataDirectory;
        this.pluginLogger = pluginLogger;
        this.companiesService = companiesService;
        this.playersService = playersService;
        this.signsService = signsService;

    }

    public void checkMigrations() {
        checkMigrations(false);
    }

    /**
     * @param freshInstallation true when the data folder had no config and no company or player data before
     *                          this start. The default company is created before this check runs, so the
     *                          folder alone can't tell a new install from old data at this point.
     */
    public void checkMigrations(boolean freshInstallation) {

        if (freshInstallation) {
            pluginLogger.info("Fresh installation; no data migration needed.");
            return;
        }

        String pluginOldVersionStr = pluginConfiguration.getString("Stonks.Version");

        if (pluginOldVersionStr == null || pluginOldVersionStr.isEmpty()) {
            if (!hasLegacyData("companies") && !hasLegacyData("players")) {
                pluginLogger.info("Fresh installation; no data migration needed.");
                return;
            }
            // If the version is null or empty, we will assume an old version
            pluginOldVersionStr = "1.0.0";
        }

        ComparableVersion pluginOldVersion = new ComparableVersion(pluginOldVersionStr);
        ComparableVersion pluginCurrentVersion = new ComparableVersion(pluginDescriptionFile.getVersion());

        if (pluginOldVersion.compareTo(pluginCurrentVersion) < 0) {
            // If the old version is lower than the current version, we will migrate the data
            migrate(pluginOldVersion, pluginCurrentVersion);
        } else {
            // If the old version is equal or higher than the current version, we will not migrate the data
            pluginLogger.info("No pending data Migrations - (" + pluginOldVersion + ") is equal or higher than the current version (" + pluginCurrentVersion + ").");
        }

    }

    /** Checked at the start of onEnable, before Stonks writes any file of its own. */
    public static boolean isFreshInstallation(File dataDirectory) {
        return !new File(dataDirectory, "config.yml").exists()
                && !hasLegacyData(dataDirectory, "companies") && !hasLegacyData(dataDirectory, "players");
    }

    private boolean hasLegacyData(String folder) {
        return hasLegacyData(pluginDataDirectory, folder);
    }

    private static boolean hasLegacyData(File dataDirectory, String folder) {
        File directory = new File(dataDirectory, folder);
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".yml"));
        return directory.exists() && (files == null || files.length > 0);
    }

    private void migrate(ComparableVersion pluginOldVersion, ComparableVersion pluginCurrentVersion) {

        if (pluginOldVersion.compareTo(new ComparableVersion("2.0.0")) >= 0) {
            pluginLogger.info("No data migration needed from " + pluginOldVersion + " to " + pluginCurrentVersion + ".");
            return;
        }

        // Old Version <= 1.1.0 && Current Version == 2.0.0
        if (pluginOldVersion.compareTo(new ComparableVersion("1.1.0")) <= 0 && pluginCurrentVersion.compareTo(new ComparableVersion("2.0.0")) == 0) {

            try {
                V110MigrationCoordinator.createProduction(pluginDataDirectory, pluginLogger, companiesService, playersService, signsService).run();
                pluginLogger.info("The plugin migrated data from version " + pluginOldVersion + " to " + pluginCurrentVersion + ".");
            } catch (RuntimeException e) {
                pluginLogger.severe("The plugin was not able to migrate the data: " + e.getMessage());
                throw e;
            }

        } else {
            pluginLogger.warning("The plugin was not able to migrate the data from version " + pluginOldVersion + " to " + pluginCurrentVersion + ".");
        }

    }

}