package me.geraldr12;

import com.google.inject.Injector;
import me.geraldr12.api.services.AutoUpdateService;
import me.geraldr12.commands.MainCommand;
import me.geraldr12.integration.AdvancedJobsAdapter;
import me.geraldr12.integration.CommerceActivityCollector;
import me.geraldr12.integration.ShopLookup;
import me.geraldr12.integration.ServerShopBridge;
import me.geraldr12.data.services.ShopLinksService;
import me.geraldr12.data.services.PriceHistoryLog;
import me.geraldr12.data.services.TreasuryService;
import me.geraldr12.economy.VaultTreasuryGateway;
import me.geraldr12.listeners.ShopLinksListener;
import me.geraldr12.integration.QuickShopAdapter;
import me.geraldr12.integration.TownFundamentalsSnapshotService;
import me.geraldr12.integration.TownyAdapter;
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
import me.geraldr12.persistence.JournalIntegrityAssessment;
import me.geraldr12.persistence.JournalIntegrityResult;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.schedulers.InterestRateScheduler;
import me.geraldr12.shadow.ShadowObservation;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.utils.ConfigAccessor;
import me.geraldr12.utils.Messages;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.inject.Inject;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class Stonks extends JavaPlugin {

    @Getter private Economy economy;
    @Getter private MarketTransactionJournal transactionJournal;
    @Getter private boolean journalReady;
    @Getter private TownFundamentalsSnapshotService townFundamentalsSnapshotService;
    private BukkitTask interestRateTask;
    private CommerceActivityCollector commerceActivityCollector;
    @Getter private ShopLookup shopLookup;
    @Getter private ShopLinksService shopLinksService;
    @Getter private TreasuryService treasuryService;
    @Getter private PriceHistoryLog priceHistoryLog;
    private ShopLinksListener shopLinksListener;
    private ShadowObservation shadowObservation;

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
        try {
            boolean freshInstallation = MigrationHandler.isFreshInstallation(getDataFolder());
            initDependencyInjectionModules();
            if (!setupEconomy()) return;

            configureConfig();
            if (!isValidInterestInterval(getConfig().getInt("Stonks.InterestInterval"))) {
                getLogger().severe("Invalid Stonks.InterestInterval; disabling Stonks.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            configureMessages();
            initializeCompaniesData();
            initializePlayersData();
            initializeSignsData();
            initializeIntegrations();
            if (!initializeTransactionJournal()) {
                getLogger().severe("Transaction journal recovery failed; market mutation remains disabled.");
                rollbackActivation();
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            migrationHandler.checkMigrations(freshInstallation);

            MainCommand mainCommand = new MainCommand(this);
            getCommand("invest").setExecutor(mainCommand);
            getCommand("invest").setTabCompleter(mainCommand);
            getCommand("company").setExecutor(new PlayerCompanyCommand(this));

            registerEvents();
            registerSchedulers();
            checkForUpdates();

            getLogger().info("Plugin successfully enabled!");
        } catch (RuntimeException exception) {
            rollbackActivation();
            getLogger().log(Level.SEVERE, "Unable to complete Stonks startup.", exception);
            if (isEnabled()) {
                getServer().getPluginManager().disablePlugin(this);
            }
        }
    }

    @Override
    public void onDisable() {
        rollbackActivation();
        if (autoUpdateService != null) {
            autoUpdateService.shutdown();
        }
        getLogger().info("Plugin successfully disabled!");
    }

    private void initializeIntegrations() {
        TownyAdapter towny = new TownyAdapter();
        QuickShopAdapter quickShop = new QuickShopAdapter();
        commerceActivityCollector = new CommerceActivityCollector(quickShop, towny);
        shopLookup = quickShop;
        treasuryService = new TreasuryService(new VaultTreasuryGateway(this),
                getDataFolder().toPath().resolve("treasury").resolve("ledger.yml"));
        treasuryService.flush();
        priceHistoryLog = new PriceHistoryLog(getDataFolder().toPath().resolve("history"),
                () -> getConfig().getBoolean("Stonks.History.Enabled", true));
        shopLinksService = new ShopLinksService(getDataFolder().toPath().resolve("shops").resolve("links.yml"));
        shopLinksListener = new ShopLinksListener(shopLinksService);
        townFundamentalsSnapshotService = new TownFundamentalsSnapshotService(
                towny, commerceActivityCollector, new AdvancedJobsAdapter());
    }

    private void activateIntegrations() {
        if (commerceActivityCollector != null) {
            commerceActivityCollector.register(this);
        }
    }

    private void rollbackActivation() {
        MarketTransactionLocks.revoke();
        if (MarketTransactionLocks.activeAdmissionCount() > 0) {
            getLogger().warning("Financial lifecycle shutdown has outstanding admissions; cleanup is non-blocking.");
        }
        journalReady = false;
        stopSchedulers();
        if (commerceActivityCollector != null) {
            commerceActivityCollector.unregister();
        }
    }

    static boolean isValidInterestInterval(int interestTime) {
        return interestTime > 0 && interestTime <= Integer.MAX_VALUE / 1200;
    }

    private void initDependencyInjectionModules() {
        BasicBinderModule guiceBinderModule = new BasicBinderModule(this);
        Injector injector = guiceBinderModule.createInjector();
        injector.injectMembers(this);
    }

    public void checkForUpdates() {
        autoUpdateService.isUpdateAvailable().thenAccept(isUpdateAvailable -> {
            if (!isUpdateAvailable) return;
            getServer().getScheduler().runTask(this, () -> {
                if (isEnabled()) {
                    getLogger().warning("An update is available! Download it at: https://modrinth.com/plugin/blockstreet");
                }
            });
        }).exceptionally(error -> {
            getLogger().warning("Unable to check for Stonks updates: " + error.getClass().getSimpleName());
            return null;
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

        if (!companiesDataFile.exists()) {
            getLogger().info("Company allocation counter is absent; allocator will reconstruct it from company files.");
        }

        // Existing logic for default company 0
        File defaultCompanyFile = new File(getDataFolder(), DataFilePath.COMPANIES.getFullPathById("0"));
        if (shouldCreateDefaultCompany(getDataFolder())) {
            try (OutputStream outStream = Files.newOutputStream(defaultCompanyFile.toPath())) {
                byte[] defaultCompanyBuffer = Objects.requireNonNull(getResource("companies/0.yml")).readAllBytes();
                outStream.write(defaultCompanyBuffer);
            } catch (IOException | NullPointerException e) {
                getLogger().warning("Unable to create default company data.");
            }
        }
    }

    static boolean shouldCreateDefaultCompany(File dataDirectory) {
        return !new File(dataDirectory, "migration/v110/state.json").exists()
                && !new File(dataDirectory, DataFilePath.COMPANIES.getFullPathById("0")).exists();
    }

    private boolean initializeTransactionJournal() {
        try {
            transactionJournal = new MarketTransactionJournal(getDataFolder());
            JournalIntegrityResult integrity = transactionJournal.inspectIntegrity();
            if (integrity.getAssessment() == JournalIntegrityAssessment.BOOTSTRAP_REQUIRED) {
                if (!transactionJournal.bootstrapDirectories()) {
                    getLogger().severe("Unable to create transaction journal directories; startup is blocked.");
                    journalReady = false;
                    return false;
                }
                integrity = transactionJournal.inspectIntegrity();
            }
            if (!isValidJournalForRecovery(integrity)) {
                getLogger().severe("Transaction journal integrity validation failed; startup is blocked.");
                journalReady = false;
                return false;
            }
            if (!runRecoveryAfterValidation(integrity,
                    transactionJournal::recoverTerminalPendingRecords)) {
                getLogger().severe("Unable to clean terminal transaction journal records.");
                journalReady = false;
                return false;
            }
            if (!runPostRecoveryValidation(integrity, transactionJournal::inspectIntegrity)) {
                getLogger().severe("Transaction journal post-recovery validation failed; startup is blocked.");
                journalReady = false;
                return false;
            }
            MarketTransactionLocks.initialize(transactionJournal);
            activateIntegrations();
            journalReady = true;
            if (!MarketTransactionLocks.activate(this, transactionJournal)) {
                getLogger().severe("Transaction lifecycle activation failed; startup is blocked.");
                journalReady = false;
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Unable to initialize transaction journal.", e);
            journalReady = false;
            return false;
        }
    }

    @FunctionalInterface
    interface RecoveryAction {
        boolean run();
    }

    static boolean runRecoveryAfterValidation(JournalIntegrityResult integrity,
                                              RecoveryAction recovery) {
        return isValidJournalForRecovery(integrity) && recovery != null && recovery.run();
    }

    static boolean runPostRecoveryValidation(JournalIntegrityResult integrity,
                                              Supplier<JournalIntegrityResult> inspection) {
        return isValidJournalForRecovery(integrity)
                && inspection != null
                && isValidJournalForRecovery(inspection.get());
    }

    static boolean isValidJournalForRecovery(JournalIntegrityResult integrity) {
        return integrity != null
                && integrity.getAssessment() == JournalIntegrityAssessment.VALID;
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
        getServer().getPluginManager().registerEvents(guiManager.getMenuListener(), this);
        if (shopLinksListener != null) {
            getServer().getPluginManager().registerEvents(shopLinksListener, this);
            if (shopLookup instanceof QuickShopAdapter quickShop) {
                shopLinksListener.registerShopDelete(this, quickShop);
            }
        }
        if (treasuryService != null && getConfig().getBoolean("Stonks.Treasury.BackServerShop", false)
                && new ServerShopBridge(treasuryService, getLogger()::warning).register(this)) {
            getLogger().info("Server shop (/shop) now pays and charges through the treasury account.");
        }
    }

    public void registerSchedulers() {
        stopSchedulers();
        int interestTime = getConfig().getInt("Stonks.InterestInterval");
        if (!isValidInterestInterval(interestTime)) {
            throw new IllegalArgumentException("Stonks.InterestInterval must be between 1 and "
                    + (Integer.MAX_VALUE / 1200));
        }
        interestRateTask = new InterestRateScheduler(this, companiesService, signsService, playersService, messages)
                .runTaskTimer(this, 20L * 10, 20L * 60 * interestTime);
        if (shadowObservation == null) {
            shadowObservation = ShadowObservation.create(this, townFundamentalsSnapshotService);
        }
        shadowObservation.register(getConfig());
    }

    public void stopSchedulers() {
        if (shadowObservation != null) {
            shadowObservation.close();
        }
        if (interestRateTask != null) {
            interestRateTask.cancel();
            interestRateTask = null;
        }
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(Economy.class);
        if (economyProvider == null || economyProvider.getProvider() == null) {
            getLogger().severe("Vault economy provider is unavailable; disabling Stonks.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        economy = economyProvider.getProvider();
        return true;
    }
}