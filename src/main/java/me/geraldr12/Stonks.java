package me.geraldr12;

import com.google.inject.Injector;
import me.geraldr12.api.services.AutoUpdateService;
import me.geraldr12.commands.MainCommand;
import me.geraldr12.commands.PlayerCompanyCommand;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.dependencyinjection.BasicBinderModule;
import me.geraldr12.enums.ConfigurationFiles;
import me.geraldr12.enums.DataFilePath;
import me.geraldr12.listeners.PlayerJoinListener;
import me.geraldr12.listeners.SignsListener;
import me.geraldr12.schedulers.InterestRateScheduler;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.utils.ConfigAccessor;
import me.geraldr12.utils.Messages;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.logging.Level;
import javax.inject.Inject;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class Stonks extends JavaPlugin {

    @Getter private Economy economy;
    private BukkitTask interestRateTask;

    @Inject @Getter private PlayerJoinListener playerJoinListener;
    @Inject @Getter private SignsListener signsListener;
    @Inject @Getter private AutoUpdateService autoUpdateService;
    @Inject @Getter private CompaniesService companiesService;
    @Inject @Getter private PlayersService playersService;
    @Inject @Getter private SignsService signsService;
    @Inject @Getter private GuiManager guiManager;
    @Inject @Getter private Messages messages;
    @Inject @Getter private MigrationHandler migrationHandler;

    @Override
    public void onEnable() {
        initDependencyInjectionModules();
        setupEconomy();

        // Standard Bukkit Command Registration
        MainCommand mainCommand = new MainCommand(this);
        getCommand("invest").setExecutor(mainCommand);
        getCommand("invest").setTabCompleter(mainCommand);

        getCommand("company").setExecutor(new PlayerCompanyCommand(this));

        registerEvents();
        configureConfig();
        configureMessages();
        initializeCompaniesData();
        initializePlayersData();
        initializeSignsData();
        registerSchedulers();
        checkForUpdates();
        migrationHandler.checkMigrations();

        getLogger().info("Plugin successfully enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Plugin successfully disabled!");
    }

    private void initDependencyInjectionModules() {
        BasicBinderModule guiceBinderModule = new BasicBinderModule(this);
        Injector injector = guiceBinderModule.createInjector();
        injector.injectMembers(this);
    }

    public void checkForUpdates() {
        autoUpdateService.isUpdateAvailable().thenAcceptAsync(isUpdateAvailable -> {
            if (isUpdateAvailable) {
                getLogger().warning("An update is available! Download it at: https://modrinth.com/plugin/blockstreet");
            }
        });
    }

    private void configureConfig(){
        if (!new File(getDataFolder(), ConfigurationFiles.CONFIG.getFileName()).exists())
            this.saveDefaultConfig();
        else {
            this.getConfig().options().copyDefaults(true);
            this.saveConfig();
        }
    }

    private void initializeCompaniesData() {
        File companiesDirectory = new File(getDataFolder(), DataFilePath.COMPANIES.getDataPath());
        File companiesDataFile = new File(getDataFolder(), DataFilePath.COMPANIES.getFullPathById("data"));

        // Create directory if it doesn't exist
        if (!companiesDirectory.exists()) {
            companiesDirectory.mkdirs();
        }

        // NEW: Initialize the data.yml file if it's missing
        if (!companiesDataFile.exists()) {
            try {
                // This creates a data.yml with nextId set to 1
                Files.writeString(companiesDataFile.toPath(), "nextId: 1");
                getLogger().info("Initialized companies/data.yml with starting ID 1.");
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not create companies/data.yml!", e);
            }
        }

        // Existing logic for default company 0
        File defaultCompanyFile = new File(getDataFolder(), DataFilePath.COMPANIES.getFullPathById("0"));
        if (!defaultCompanyFile.exists()) {
            try (OutputStream outStream = Files.newOutputStream(defaultCompanyFile.toPath())) {
                byte[] defaultCompanyBuffer = Objects.requireNonNull(getResource("companies/0.yml")).readAllBytes();
                outStream.write(defaultCompanyBuffer);
            } catch (IOException | NullPointerException e) {
                getLogger().warning("Unable to create default company data.");
            }
        }
    }

    private void initializePlayersData() {
        File playersDirectory = new File(getDataFolder(), DataFilePath.PLAYERS.getDataPath());
        if (!playersDirectory.exists()) playersDirectory.mkdir();
    }

    private void initializeSignsData() {
        File signsDirectory = new File(getDataFolder(), DataFilePath.SIGNS.getDataPath());
        if (!signsDirectory.exists()) signsDirectory.mkdir();
    }

    private void configureMessages() {
        ConfigAccessor messagesConfig = new ConfigAccessor(this, ConfigurationFiles.MESSAGES.getFileName());
        if(!new File(getDataFolder(), ConfigurationFiles.MESSAGES.getFileName()).exists())
            messagesConfig.saveDefaultConfig();
    }

    private void registerEvents() {
        getServer().getPluginManager().registerEvents(playerJoinListener, this);
        getServer().getPluginManager().registerEvents(signsListener, this);
    }

    public void registerSchedulers() {
        int interestTime = getConfig().getInt("Stonks.InterestInterval");
        interestRateTask = new InterestRateScheduler(this, companiesService, signsService, playersService, messages)
                .runTaskTimerAsynchronously(this, 20L*10, 20L*60*interestTime);
    }

    public void stopSchedulers() { if (interestRateTask != null) interestRateTask.cancel(); }

    private void setupEconomy() {
        RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(Economy.class);
        if (economyProvider != null) economy = economyProvider.getProvider();
        else { getServer().getPluginManager().disablePlugin(this); }
    }
}