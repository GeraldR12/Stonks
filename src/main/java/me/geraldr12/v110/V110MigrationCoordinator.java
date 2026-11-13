package me.geraldr12.v110;

import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.dao.PlayerDao;
import me.geraldr12.data.dao.SignDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.utils.SizedStack;
import me.geraldr12.v110.LegacyMigrationPlan.Company;
import me.geraldr12.v110.LegacyMigrationPlan.Player;
import me.geraldr12.v110.LegacyMigrationPlan.Position;
import me.geraldr12.v110.LegacyMigrationPlan.Sign;
import me.geraldr12.v110.MigrationState.MigrationException;
import me.geraldr12.v110.MigrationState.Phase;
import me.geraldr12.v110.destination.CompanyDestination;
import me.geraldr12.v110.destination.PlayerDestination;
import me.geraldr12.v110.destination.ProductionCompanyDestination;
import me.geraldr12.v110.destination.ProductionPlayerDestination;
import me.geraldr12.v110.destination.ProductionSignDestination;
import me.geraldr12.v110.destination.SignDestination;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;

public final class V110MigrationCoordinator {

    static final String EXAMPLE_COMPANY_NAME = "Blocks Corp";
    private static final int EXAMPLE_COMPANY_ID = 0;
    private static final List<String> SOURCES = List.of("companies.yml", "players.yml", "signs.yml");
    private static final String ABSENT = "-";

    private final File dataDirectory;
    private final Path backupDirectory;
    private final Path manifestFile;
    private final Logger logger;
    private final CompanyDestination companies;
    private final PlayerDestination players;
    private final SignDestination signs;
    private final Function<String, OfflinePlayer> offlinePlayers;
    private final BiFunction<ConfigurationSection, String, Location> locations;
    private final Function<File, ConfigurationSection> yamlLoader;
    private final MigrationFileOps fileOps;

    public V110MigrationCoordinator(File dataDirectory, Logger logger, CompanyDestination companies,
                                    PlayerDestination players, SignDestination signs,
                                    Function<String, OfflinePlayer> offlinePlayers,
                                    BiFunction<ConfigurationSection, String, Location> locations,
                                    Function<File, ConfigurationSection> yamlLoader,
                                    MigrationFileOps fileOps) {
        this.dataDirectory = dataDirectory;
        this.backupDirectory = dataDirectory.toPath().resolve("migration/v110/backup");
        this.manifestFile = backupDirectory.resolve("manifest.sha256");
        this.logger = logger;
        this.companies = companies;
        this.players = players;
        this.signs = signs;
        this.offlinePlayers = offlinePlayers;
        this.locations = locations;
        this.yamlLoader = yamlLoader;
        this.fileOps = fileOps;
    }

    @SuppressWarnings("deprecation")
    public static V110MigrationCoordinator createProduction(File dataDirectory, Logger logger,
                                                            CompaniesService companiesService,
                                                            PlayersService playersService,
                                                            SignsService signsService) {
        return new V110MigrationCoordinator(dataDirectory, logger,
                new ProductionCompanyDestination(companiesService, dataDirectory),
                new ProductionPlayerDestination(playersService, dataDirectory),
                new ProductionSignDestination(signsService, dataDirectory),
                Bukkit::getOfflinePlayer,
                ConfigurationSection::getLocation,
                LegacyMigrationPlan::loadYaml,
                MigrationFileOps.DEFAULT);
    }

    public void run() {
        MigrationState state;
        try {
            state = MigrationState.load(dataDirectory, fileOps);
        } catch (MigrationException e) {
            logger.severe("Migration v110 cannot start: " + e.getMessage());
            throw e;
        }
        if (state.isDone()) {
            logger.info("Migration v110 already completed.");
            return;
        }
        if (state.getFailureReason() != null) {
            logger.warning("Resuming migration v110 after failure at " + state.getFailurePhase() + ": "
                    + state.getFailureReason());
        }
        try {
            execute(state);
            logger.info("Migration v110 completed.");
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            logger.severe("Migration v110 failed at " + state.getPhase() + ": " + reason);
            try {
                state.recordFailure(reason);
            } catch (RuntimeException saveFailure) {
                e.addSuppressed(saveFailure);
                logger.severe("Migration v110 could not record the failure: " + saveFailure.getMessage());
            }
            throw e instanceof MigrationException migrationException
                    ? migrationException
                    : new MigrationException(reason, e);
        }
    }

    private void execute(MigrationState state) {
        if (state.getPhase() == Phase.BACKUP) {
            backup();
            state.advanceTo(Phase.STAGE);
        }
        Map<String, String> manifest = readVerifiedManifest();
        if (state.getPhase() != Phase.ARCHIVE) {
            requireSourcesMatch(manifest);
            LegacyMigrationPlan plan = LegacyMigrationPlan.parse(backupDirectory.toFile(), yamlLoader, offlinePlayers, locations);
            freezeOrVerifyIdentities(state, plan);
            if (state.getPhase() == Phase.STAGE) {
                state.advanceTo(Phase.APPLY);
            }
            if (state.getPhase() == Phase.APPLY) {
                checkConflicts(plan);
                apply(plan);
                state.advanceTo(Phase.VERIFY);
            }
            verify(plan);
            state.advanceTo(Phase.ARCHIVE);
        }
        archive(manifest);
        state.advanceTo(Phase.DONE);
    }

    private void freezeOrVerifyIdentities(MigrationState state, LegacyMigrationPlan plan) {
        StringBuilder identities = new StringBuilder();
        plan.players().stream().sorted(java.util.Comparator.comparing(Player::name))
                .forEach(player -> identities.append(player.id()).append("  ").append(player.name()).append('\n'));
        Path file = dataDirectory.toPath().resolve("migration/v110/identities.txt");
        try {
            if (state.getPhase() == Phase.STAGE) {
                fileOps.writeAtomically(file, identities.toString().getBytes(StandardCharsets.UTF_8));
            } else if (!Files.exists(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(identities.toString())) {
                throw new MigrationException("Migration identities are missing or differ from the staged plan", null);
            }
        } catch (IOException e) {
            throw new MigrationException("Migration identities are unreadable or could not be saved", e);
        }
    }

    private void backup() {
        try {
            Files.createDirectories(backupDirectory);
            if (Files.exists(manifestFile)) {
                return;
            }
            StringBuilder manifest = new StringBuilder();
            for (String name : SOURCES) {
                Path source = source(name);
                if (!Files.exists(source)) {
                    manifest.append(ABSENT).append("  ").append(name).append('\n');
                    continue;
                }
                String hash = sha256(source);
                Path target = backupDirectory.resolve(name);
                fileOps.copy(source, target);
                if (!hash.equals(sha256(target))) {
                    throw new MigrationException("Backup bytes differ from " + name, null);
                }
                manifest.append(hash).append("  ").append(name).append('\n');
            }
            fileOps.writeAtomically(manifestFile, manifest.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MigrationException("Backup failed: " + e.getMessage(), e);
        }
    }

    private Map<String, String> readVerifiedManifest() {
        Map<String, String> manifest = new HashMap<>();
        try {
            for (String line : Files.readAllLines(manifestFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split(" {2}", 2);
                if (parts.length != 2 || !SOURCES.contains(parts[1])) {
                    throw new MigrationException("Backup manifest is malformed", null);
                }
                manifest.put(parts[1], parts[0]);
            }
            if (manifest.size() != SOURCES.size()) {
                throw new MigrationException("Backup manifest is incomplete", null);
            }
            for (String name : SOURCES) {
                String hash = manifest.get(name);
                Path backup = backupDirectory.resolve(name);
                if (ABSENT.equals(hash)) {
                    if (Files.exists(backup)) {
                        throw new MigrationException("Backup exists for absent manifest entry: " + name, null);
                    }
                } else if (!hash.equals(sha256(backup))) {
                    throw new MigrationException("Backup does not match the manifest: " + name, null);
                }
            }
        } catch (IOException e) {
            throw new MigrationException("Backup manifest is unreadable: " + e.getMessage(), e);
        }
        return manifest;
    }

    private void requireSourcesMatch(Map<String, String> manifest) {
        try {
            for (String name : SOURCES) {
                Path source = source(name);
                String expected = manifest.get(name);
                if (ABSENT.equals(expected)) {
                    if (Files.exists(source)) {
                        throw new MigrationException("Source appeared after backup: " + name, null);
                    }
                } else if (!Files.exists(source) || !expected.equals(sha256(source))) {
                    throw new MigrationException("Source changed after backup: " + name, null);
                }
            }
        } catch (IOException e) {
            throw new MigrationException("Source is unreadable: " + e.getMessage(), e);
        }
    }

    private void checkConflicts(LegacyMigrationPlan plan) {
        for (Company company : plan.companies()) {
            Optional<CompanyDao> existing = companies.findById(company.id());
            if (existing.isPresent() && !matches(existing.get(), company)) {
                throw new MigrationException("Conflict: company " + company.id() + " differs", null);
            }
        }
        if (removesExample(plan)) {
            companies.findById(EXAMPLE_COMPANY_ID)
                    .filter(example -> !EXAMPLE_COMPANY_NAME.equals(example.getName())
                            || example.getOwnerUuid() != null
                            || example.getRisk() != 3
                            || example.getTotalShares() != 100
                            || example.getAvailableShares() != 99
                            || (Double.compare(example.getInitialSharePrice(), 1000) != 0
                            && Double.compare(example.getInitialSharePrice(), 40) != 0))
                    .ifPresent(example -> {
                        throw new MigrationException("Conflict: company 0 is not the default example", null);
                    });
        }
        for (Player player : plan.players()) {
            Optional<PlayerDao> existing = players.findById(player.id());
            if (existing.isEmpty()) {
                continue;
            }
            for (Position position : player.positions()) {
                findInvestment(existing.get(), position.companyId())
                        .filter(investment -> investment.getSharesAmount() != position.amount())
                        .ifPresent(investment -> {
                            throw new MigrationException("Conflict: player " + player.name() + " position in company "
                                    + position.companyId() + " differs", null);
                        });
            }
        }
        for (Sign sign : plan.signs()) {
            Optional<SignDao> existing = signs.findById(sign.id());
            if (existing.isPresent() && !matches(existing.get(), sign)) {
                throw new MigrationException("Conflict: sign " + sign.id() + " differs", null);
            }
        }
    }

    private void apply(LegacyMigrationPlan plan) {
        if (removesExample(plan) && companies.exists(EXAMPLE_COMPANY_ID)
                && !companies.delete(EXAMPLE_COMPANY_ID)) {
            throw new MigrationException("Failed to remove the default example company", null);
        }
        for (Company company : plan.companies()) {
            if (companies.findById(company.id()).isEmpty() && !companies.save(toDao(company))) {
                throw new MigrationException("Failed to save company " + company.id(), null);
            }
        }
        if (!plan.companies().isEmpty()) {
            companies.raiseNextIdTo(plan.maxCompanyId() + 1L);
        }
        for (Player player : plan.players()) {
            applyPlayer(player);
        }
        for (Sign sign : plan.signs()) {
            if (signs.findById(sign.id()).isEmpty() && !signs.save(toDao(sign))) {
                throw new MigrationException("Failed to save sign " + sign.id(), null);
            }
        }
    }

    private void applyPlayer(Player player) {
        Optional<PlayerDao> existing = players.findById(player.id());
        List<InvestmentDao> investments = new ArrayList<>(
                existing.map(PlayerDao::getInvestments).orElseGet(ArrayList::new));
        boolean changed = existing.isEmpty();
        for (Position position : player.positions()) {
            boolean held = investments.stream().anyMatch(item -> item.getCompanyId() == position.companyId());
            if (!held) {
                investments.add(new InvestmentDao(position.companyId(), position.amount(), 0.0));
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        PlayerDao updated = new PlayerDao(player.id().toString(),
                existing.map(PlayerDao::getName).orElse(player.name()),
                investments,
                existing.map(PlayerDao::getBlockedNotifications).orElseGet(HashSet::new));
        if (!players.save(updated)) {
            throw new MigrationException("Failed to save player " + player.name(), null);
        }
    }

    private void verify(LegacyMigrationPlan plan) {
        for (Company company : plan.companies()) {
            if (companies.findById(company.id()).filter(found -> matches(found, company)).isEmpty()) {
                throw new MigrationException("Verification failed for company " + company.id(), null);
            }
        }
        if (removesExample(plan) && companies.exists(EXAMPLE_COMPANY_ID)) {
            throw new MigrationException("Verification failed: example company is still present", null);
        }
        for (Player player : plan.players()) {
            PlayerDao found = players.findById(player.id()).orElseThrow(
                    () -> new MigrationException("Verification failed for player " + player.name(), null));
            for (Position position : player.positions()) {
                if (findInvestment(found, position.companyId())
                        .filter(item -> item.getSharesAmount() == position.amount()).isEmpty()) {
                    throw new MigrationException("Verification failed for player " + player.name()
                            + " in company " + position.companyId(), null);
                }
            }
        }
        for (Sign sign : plan.signs()) {
            if (signs.findById(sign.id()).filter(found -> matches(found, sign)).isEmpty()) {
                throw new MigrationException("Verification failed for sign " + sign.id(), null);
            }
        }
    }

    private void archive(Map<String, String> manifest) {
        try {
            for (String name : SOURCES) {
                String expected = manifest.get(name);
                if (ABSENT.equals(expected)) {
                    continue;
                }
                Path source = source(name);
                Path archived = source(name + ".old");
                if (Files.exists(source)) {
                    if (!expected.equals(sha256(source))) {
                        throw new MigrationException("Source changed before archive: " + name, null);
                    }
                    fileOps.rename(source, archived);
                } else if (!Files.exists(archived) || !expected.equals(sha256(archived))) {
                    throw new MigrationException("Archive does not match the manifest: " + name, null);
                }
            }
        } catch (IOException e) {
            throw new MigrationException("Archive failed: " + e.getMessage(), e);
        }
    }

    private boolean removesExample(LegacyMigrationPlan plan) {
        return !plan.companies().isEmpty() && !plan.hasCompany(EXAMPLE_COMPANY_ID);
    }

    private Optional<InvestmentDao> findInvestment(PlayerDao player, int companyId) {
        return player.getInvestments().stream().filter(item -> item.getCompanyId() == companyId).findFirst();
    }

    private boolean matches(CompanyDao found, Company company) {
        return found.getId() == company.id()
                && Objects.equals(found.getName(), company.name())
                && found.getRisk() == company.risk()
                && found.getTotalShares() == company.shares()
                && found.getAvailableShares() == company.shares()
                && Double.compare(found.getInitialSharePrice(), company.price()) == 0
                && Double.compare(found.getCurrentSharePrice(), company.price()) == 0;
    }

    private boolean matches(SignDao found, Sign sign) {
        return found.getCompanyId() == sign.companyId() && Objects.equals(found.getLocation(), sign.location());
    }

    private CompanyDao toDao(Company company) {
        return CompanyDao.builder()
                .id(company.id())
                .name(company.name())
                .icon(Material.EMERALD)
                .risk(company.risk())
                .totalShares(company.shares())
                .availableShares(company.shares())
                .initialSharePrice(company.price())
                .currentSharePrice(company.price())
                .historic(new SizedStack<>(500))
                .build();
    }

    private SignDao toDao(Sign sign) {
        return SignDao.builder().id(sign.id()).companyId(sign.companyId()).location(sign.location()).build();
    }

    private Path source(String name) {
        return dataDirectory.toPath().resolve(name);
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
