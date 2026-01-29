package me.geraldr12;

import com.google.inject.name.Named;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.v110.CompaniesMigrator;
import me.geraldr12.v110.PlayersMigrator;
import me.geraldr12.v110.SignsMigrator;
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

        String pluginOldVersionStr = pluginConfiguration.getString("Stonks.Version");

        if (pluginOldVersionStr == null || pluginOldVersionStr.isEmpty()) {
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

    private void migrate(ComparableVersion pluginOldVersion, ComparableVersion pluginCurrentVersion) {

        // Old Version <= 1.1.0 && Current Version == 2.0.0
        if (pluginOldVersion.compareTo(new ComparableVersion("1.1.0")) <= 0 && pluginCurrentVersion.compareTo(new ComparableVersion("2.0.0")) == 0) {

            Migrator companiesMigrator = new CompaniesMigrator(pluginDataDirectory, companiesService);
            companiesMigrator.migrate();
            companiesMigrator.archiveOldData();

            Migrator playersMigrator = new PlayersMigrator(pluginDataDirectory, playersService);
            playersMigrator.migrate();
            playersMigrator.archiveOldData();

            Migrator signsMigrator = new SignsMigrator(pluginDataDirectory, signsService);
            signsMigrator.migrate();
            signsMigrator.archiveOldData();

            pluginLogger.info("The plugin migrated data from version " + pluginOldVersion + " to " + pluginCurrentVersion + ".");

        } else {
            pluginLogger.warning("The plugin was not able to migrate the data from version " + pluginOldVersion + " to " + pluginCurrentVersion + ".");
        }

    }

}
