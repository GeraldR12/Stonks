package me.geraldr12.v110;

import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.dao.LocationDao;
import me.geraldr12.data.dao.PlayerDao;
import me.geraldr12.data.dao.SignDao;
import me.geraldr12.v110.MigrationState.MigrationException;
import me.geraldr12.v110.destination.CompanyDestination;
import me.geraldr12.v110.destination.PlayerDestination;
import me.geraldr12.v110.destination.SignDestination;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V110MigrationCoordinatorTest {

    private static final String COMPANIES = """
            Companies:
              '5':
                Name: Alpha
                Price: 12.5
                Risk: 2
                AvailableStocks: 100
              '9':
                Name: Beta
                Price: 40
                Risk: 1
                AvailableStocks: -1
            """;
    private static final String PLAYERS = """
            Players:
              Steve:
                Companies:
                  '5':
                    Amount: 7
                  '9':
                    Amount: 3
              Alex:
                Companies:
                  '9':
                    Amount: 1
                  '5':
                    Amount: 0
            """;
    private static final String SIGNS = """
            Signs:
              '4':
                CompanyId: 9
                Location: world,1,2,3
              '11':
                CompanyId: 5
                Location: world,4,5,6
            """;

    private static final Logger LOGGER = Logger.getAnonymousLogger();

    static {
        LOGGER.setLevel(Level.OFF);
    }

    @TempDir
    Path tempDirectory;

    private final class Sandbox {
        final Path dir;
        final FakeCompanies companies = new FakeCompanies();
        final FakePlayers players = new FakePlayers();
        final FakeSigns signs = new FakeSigns();
        final FaultyOps ops = new FaultyOps();
        Function<String, OfflinePlayer> offline = V110MigrationCoordinatorTest::offlinePlayer;
        BiFunction<ConfigurationSection, String, Location> locations = V110MigrationCoordinatorTest::parseLocation;

        Sandbox(String name, String companiesYml, String playersYml, String signsYml) throws IOException {
            dir = Files.createDirectories(tempDirectory.resolve(name));
            write("companies.yml", companiesYml);
            write("players.yml", playersYml);
            write("signs.yml", signsYml);
        }

        void write(String file, String content) throws IOException {
            if (content != null) {
                Files.writeString(dir.resolve(file), content, StandardCharsets.UTF_8);
            }
        }

        void run() {
            new V110MigrationCoordinator(dir.toFile(), LOGGER, companies, players, signs, offline, locations, V110MigrationCoordinatorTest::loadYaml, ops).run();
        }

        String phase() throws IOException {
            Path state = dir.resolve("migration/v110/state.json");
            if (!Files.exists(state)) {
                return "NONE";
            }
            String content = Files.readString(state);
            int start = content.indexOf("\"phase\":\"") + 9;
            return content.substring(start, content.indexOf('"', start));
        }

        int mutations() {
            return companies.saves + companies.deletes + companies.counterRaises + players.saves + signs.saves;
        }

        Map<String, String> snapshot() throws IOException {
            Map<String, String> result = new TreeMap<>();
            companies.rows.forEach((id, row) -> result.put("company/" + id, describe(row)));
            players.rows.forEach((id, row) -> result.put("player/" + id, describe(row)));
            signs.rows.forEach((id, row) -> result.put("sign/" + id, row.toString()));
            result.put("counter", String.valueOf(companies.nextId));
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    String relative = dir.relativize(path).toString().replace('\\', '/');
                    if (!relative.endsWith("state.json")) {
                        result.put("file/" + relative, Files.readString(path));
                    }
                }
            }
            return result;
        }
    }

    private Sandbox standard(String name) throws IOException {
        Sandbox sandbox = new Sandbox(name, COMPANIES, PLAYERS, SIGNS);
        sandbox.companies.put(exampleCompany("Blocks Corp"));
        return sandbox;
    }

    private static CompanyDao exampleCompany(String name) {
        return CompanyDao.builder().id(0).name(name).icon(Material.EMERALD).risk(3).totalShares(100).availableShares(99)
                .initialSharePrice(1000).currentSharePrice(1000).build();
    }

    private static CompanyDao company(int id, String name, int risk, int shares, double price) {
        return CompanyDao.builder().id(id).name(name).icon(Material.EMERALD).risk(risk).totalShares(shares)
                .availableShares(shares).initialSharePrice(price).currentSharePrice(price).build();
    }

    private static String describe(CompanyDao row) {
        return row.getId() + "|" + row.getName() + "|" + row.getRisk() + "|" + row.getTotalShares() + "|"
                + row.getAvailableShares() + "|" + row.getInitialSharePrice() + "|" + row.getCurrentSharePrice();
    }

    private static String describe(PlayerDao row) {
        return row.getUniqueId() + "|" + row.getName() + "|" + row.getInvestments().stream()
                .map(InvestmentDao::toString).sorted().collect(Collectors.joining(","));
    }

    @SuppressWarnings("unchecked")
    private static ConfigurationSection loadYaml(java.io.File file) {
        MemoryConfiguration config = new MemoryConfiguration();
        try {
            Map<String, Object> root = new Yaml().load(Files.readString(file.toPath()));
            if (root != null) {
                root.forEach((key, value) -> {
                    if (value instanceof Map<?, ?> map) {
                        config.createSection(key, (Map<String, Object>) map);
                    } else {
                        config.set(key, value);
                    }
                });
            }
        } catch (IOException | YAMLException | ClassCastException e) {
            throw new MigrationException("Legacy file is unreadable: " + file.getName(), e);
        }
        return config;
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static OfflinePlayer offlinePlayer(String name) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(uuid(name));
        return player;
    }

    private static Location parseLocation(ConfigurationSection config, String path) {
        String value = config.getString(path);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        World world = mock(World.class);
        when(world.getName()).thenReturn(parts[0]);
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getX()).thenReturn(Double.parseDouble(parts[1]));
        when(location.getY()).thenReturn(Double.parseDouble(parts[2]));
        when(location.getZ()).thenReturn(Double.parseDouble(parts[3]));
        return location;
    }

    private void assertSourcesIntact(Sandbox sandbox) throws IOException {
        assertEquals(COMPANIES, Files.readString(sandbox.dir.resolve("companies.yml")));
        assertEquals(PLAYERS, Files.readString(sandbox.dir.resolve("players.yml")));
        assertEquals(SIGNS, Files.readString(sandbox.dir.resolve("signs.yml")));
    }

    @Test
    void completesKeepingLegacyIdsAndReferences() throws IOException {
        Sandbox sandbox = standard("full");
        sandbox.companies.nextId = 3L;

        sandbox.run();

        assertEquals("DONE", sandbox.phase());
        assertFalse(sandbox.companies.rows.containsKey(0), "default example removed");
        assertEquals(describe(company(5, "Alpha", 2, 100, 12.5)), describe(sandbox.companies.rows.get(5)));
        assertEquals(describe(company(9, "Beta", 1, -1, 40)), describe(sandbox.companies.rows.get(9)));
        assertEquals(10L, sandbox.companies.nextId);

        PlayerDao steve = sandbox.players.rows.get(uuid("Steve"));
        assertEquals(2, steve.getInvestments().size());
        assertEquals(7, investment(steve, 5).getSharesAmount());
        assertEquals(3, investment(steve, 9).getSharesAmount());
        assertEquals(0.0, investment(steve, 5).getAverageBuyPrice());
        assertEquals(1, sandbox.players.rows.get(uuid("Alex")).getInvestments().size(), "zero position is not created");

        assertEquals(9, sandbox.signs.rows.get(4).getCompanyId());
        assertEquals(new LocationDao("world", 4, 5, 6), sandbox.signs.rows.get(11).getLocation());

        assertEquals(COMPANIES, Files.readString(sandbox.dir.resolve("companies.yml.old")));
        assertEquals(COMPANIES, Files.readString(sandbox.dir.resolve("migration/v110/backup/companies.yml")));
        assertFalse(Files.exists(sandbox.dir.resolve("companies.yml")));
        assertTrue(Files.exists(sandbox.dir.resolve("migration/v110/backup/manifest.sha256")));
    }

    private static InvestmentDao investment(PlayerDao player, long companyId) {
        return player.getInvestments().stream().filter(item -> item.getCompanyId() == companyId).findFirst().orElseThrow();
    }

    private record Fault(String name, boolean sourcesStayInPlace, Consumer<Sandbox> install) {
    }

    private static List<Fault> faults() {
        List<Fault> faults = new ArrayList<>();
        for (String file : List.of("companies.yml", "players.yml", "signs.yml")) {
            faults.add(new Fault("backup copy of " + file, true, s -> s.ops.failCopyOf = file));
            faults.add(new Fault("archive rename of " + file, false, s -> s.ops.failRenameOf = file));
        }
        faults.add(new Fault("manifest write", true, s -> s.ops.failWriteOf = "manifest.sha256"));
        for (int write = 1; write <= 5; write++) {
            int target = write;
            faults.add(new Fault("state write number " + write + " fails", target < 5, s -> {
                s.ops.failWriteOf = "state.json";
                s.ops.failOnWriteNumber = target;
            }));
            faults.add(new Fault("process stops after state write number " + write, target < 4, s -> {
                s.ops.crashAfterWriteOf = "state.json";
                s.ops.failOnWriteNumber = target;
            }));
        }
        faults.add(new Fault("stage player resolution", true, s -> {
            Function<String, OfflinePlayer> original = s.offline;
            s.offline = new FailOnce<>(original)::apply;
        }));
        faults.add(new Fault("stage location read", true, s -> {
            BiFunction<ConfigurationSection, String, Location> original = s.locations;
            boolean[] failed = {false};
            s.locations = (config, path) -> {
                if (!failed[0]) {
                    failed[0] = true;
                    throw new IllegalStateException("world is not loaded");
                }
                return original.apply(config, path);
            };
        }));
        faults.add(new Fault("example company delete", true, s -> s.companies.failDelete = true));
        faults.add(new Fault("first company save", true, s -> s.companies.failSaveOf = 5));
        faults.add(new Fault("second company save", true, s -> s.companies.failSaveOf = 9));
        faults.add(new Fault("company save throws", true, s -> s.companies.throwSaveOf = 9));
        faults.add(new Fault("id counter", true, s -> s.companies.failCounter = true));
        faults.add(new Fault("first player save", true, s -> s.players.failSaveOf = uuid("Steve")));
        faults.add(new Fault("second player save", true, s -> s.players.failSaveOf = uuid("Alex")));
        faults.add(new Fault("first sign save", true, s -> s.signs.failSaveOf = 4));
        faults.add(new Fault("second sign save", true, s -> s.signs.failSaveOf = 11));
        faults.add(new Fault("company lost before verify", true, s -> s.companies.hideDuringVerifyOf = 9));
        faults.add(new Fault("position changed before verify", true, s -> s.players.corruptAfterSave = uuid("Steve")));
        faults.add(new Fault("sign lost before verify", true, s -> s.signs.hideAfterSaveOf = 11));
        return faults;
    }

    private static final class FailOnce<T, R> {
        private final Function<T, R> delegate;
        private boolean failed;

        FailOnce(Function<T, R> delegate) {
            this.delegate = delegate;
        }

        R apply(T value) {
            if (!failed) {
                failed = true;
                throw new IllegalStateException("resolver failed");
            }
            return delegate.apply(value);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyFaultResumesToTheCleanResult() {
        return faults().stream().map(fault -> DynamicTest.dynamicTest(fault.name(), () -> {
            String id = UUID.randomUUID().toString();
            Sandbox clean = standard("clean-" + id);
            clean.companies.nextId = 3L;
            clean.run();

            Sandbox sandbox = standard("fault-" + id);
            sandbox.companies.nextId = 3L;
            fault.install().accept(sandbox);

            assertThrows(RuntimeException.class, sandbox::run, "fault must surface: " + fault.name());
            assertFalse(sandbox.phase().equals("DONE"), "never DONE after a failure");
            if (fault.sourcesStayInPlace()) {
                assertSourcesIntact(sandbox);
            }

            sandbox.ops.clear();
            sandbox.companies.clearFaults();
            sandbox.players.clearFaults();
            sandbox.signs.clearFaults();
            sandbox.run();

            assertEquals("DONE", sandbox.phase());
            assertEquals(clean.snapshot(), sandbox.snapshot(), "resumed result equals a clean run");
        }));
    }

    @Test
    void failureIsRecordedInStateAndResumedFromSamePhase() throws IOException {
        Sandbox sandbox = standard("recorded");
        sandbox.companies.failSaveOf = 5;

        assertThrows(MigrationException.class, sandbox::run);

        String state = Files.readString(sandbox.dir.resolve("migration/v110/state.json"));
        assertEquals("APPLY", sandbox.phase());
        assertTrue(state.contains("Failed to save company 5"), state);
    }

    @Test
    void runAfterDoneChangesNothing() throws IOException {
        Sandbox sandbox = standard("done");
        sandbox.run();
        Map<String, String> before = sandbox.snapshot();
        String state = Files.readString(sandbox.dir.resolve("migration/v110/state.json"));
        int mutations = sandbox.mutations();

        sandbox.run();

        assertEquals(before, sandbox.snapshot());
        assertEquals(state, Files.readString(sandbox.dir.resolve("migration/v110/state.json")));
        assertEquals(mutations, sandbox.mutations());
    }

    private record Corrupt(String name, String companies, String players, String signs) {
    }

    @TestFactory
    Stream<DynamicTest> corruptSourceTouchesNoDestination() {
        List<Corrupt> cases = List.of(
                new Corrupt("non numeric company id", "Companies:\n  abc:\n    Name: A\n    Price: 1.0\n    Risk: 1\n    AvailableStocks: 1\n", PLAYERS, SIGNS),
                new Corrupt("negative company id", "Companies:\n  '-2':\n    Name: A\n    Price: 1.0\n    Risk: 1\n    AvailableStocks: 1\n", null, null),
                new Corrupt("blank name", "Companies:\n  '5':\n    Name: ' '\n    Price: 1.0\n    Risk: 1\n    AvailableStocks: 1\n", null, null),
                new Corrupt("NaN price", "Companies:\n  '5':\n    Name: A\n    Price: .nan\n    Risk: 1\n    AvailableStocks: 1\n", null, null),
                new Corrupt("infinite price", "Companies:\n  '5':\n    Name: A\n    Price: .inf\n    Risk: 1\n    AvailableStocks: 1\n", null, null),
                new Corrupt("negative price", "Companies:\n  '5':\n    Name: A\n    Price: -1.0\n    Risk: 1\n    AvailableStocks: 1\n", null, null),
                new Corrupt("missing price", "Companies:\n  '5':\n    Name: A\n    Risk: 1\n    AvailableStocks: 1\n", null, null),
                new Corrupt("text price", "Companies:\n  '5':\n    Name: A\n    Price: abc\n    Risk: 1\n    AvailableStocks: 1\n", null, null),
                new Corrupt("decimal shares", "Companies:\n  '5':\n    Name: A\n    Price: 1.0\n    Risk: 1\n    AvailableStocks: 1.5\n", null, null),
                new Corrupt("shares below unlimited", "Companies:\n  '5':\n    Name: A\n    Price: 1.0\n    Risk: 1\n    AvailableStocks: -2\n", null, null),
                new Corrupt("negative risk", "Companies:\n  '5':\n    Name: A\n    Price: 1.0\n    Risk: -1\n    AvailableStocks: 1\n", null, null),
                new Corrupt("broken yaml", "Companies: [unclosed\n  - : :", null, null),
                new Corrupt("scalar section", "Companies: text\n", null, null),
                new Corrupt("player references unknown company", COMPANIES, "Players:\n  Steve:\n    Companies:\n      '77':\n        Amount: 1\n", null),
                new Corrupt("negative position", COMPANIES, "Players:\n  Steve:\n    Companies:\n      '5':\n        Amount: -1\n", null),
                new Corrupt("text position", COMPANIES, "Players:\n  Steve:\n    Companies:\n      '5':\n        Amount: many\n", null),
                new Corrupt("malformed player companies", COMPANIES, "Players:\n  Steve:\n    Companies: nope\n", null),
                new Corrupt("sign references unknown company", COMPANIES, null, "Signs:\n  '1':\n    CompanyId: 77\n    Location: world,1,2,3\n"),
                new Corrupt("sign without location", COMPANIES, null, "Signs:\n  '1':\n    CompanyId: 5\n"),
                new Corrupt("sign with text company", COMPANIES, null, "Signs:\n  '1':\n    CompanyId: x\n    Location: world,1,2,3\n"));
        return cases.stream().map(item -> DynamicTest.dynamicTest(item.name(), () -> {
            Sandbox sandbox = new Sandbox("corrupt-" + UUID.randomUUID(), item.companies(), item.players(), item.signs());
            sandbox.companies.put(exampleCompany("Blocks Corp"));

            assertThrows(MigrationException.class, sandbox::run);

            assertEquals(0, sandbox.mutations(), "destination untouched");
            assertEquals("STAGE", sandbox.phase());
            assertTrue(sandbox.companies.rows.containsKey(0));
            if (item.companies() != null) {
                assertEquals(item.companies(), Files.readString(sandbox.dir.resolve("companies.yml")));
            }
        }));
    }

    private record Conflict(String name, Consumer<Sandbox> seed) {
    }

    @TestFactory
    Stream<DynamicTest> conflictAbortsBeforeAnyWrite() {
        List<Conflict> cases = List.of(
                new Conflict("company differs", s -> s.companies.put(company(9, "Other", 1, -1, 40))),
                new Conflict("example has another name", s -> s.companies.put(exampleCompany("Player Corp"))),
                new Conflict("position differs", s -> s.players.rows.put(uuid("Alex"), new PlayerDao(uuid("Alex").toString(), "Alex",
                        new ArrayList<>(List.of(new InvestmentDao(9, 2, 1.0))), new HashSet<>()))),
                new Conflict("sign differs", s -> s.signs.rows.put(11, SignDao.builder().id(11).companyId(9)
                        .location(new LocationDao("world", 4, 5, 6)).build())));
        return cases.stream().map(item -> DynamicTest.dynamicTest(item.name(), () -> {
            Sandbox sandbox = standard("conflict-" + UUID.randomUUID());
            item.seed().accept(sandbox);
            Map<String, String> rowsBefore = rows(sandbox);

            MigrationException failure = assertThrows(MigrationException.class, sandbox::run);

            assertTrue(failure.getMessage().startsWith("Conflict"), failure.getMessage());
            assertEquals(0, sandbox.mutations());
            assertEquals(rowsBefore, rows(sandbox));
            assertSourcesIntact(sandbox);
        }));
    }

    private static Map<String, String> rows(Sandbox sandbox) {
        Map<String, String> result = new LinkedHashMap<>();
        sandbox.companies.rows.forEach((id, row) -> result.put("c" + id, describe(row)));
        sandbox.players.rows.forEach((id, row) -> result.put("p" + id, describe(row)));
        sandbox.signs.rows.forEach((id, row) -> result.put("s" + id, row.toString()));
        return result;
    }

    @Test
    void equalExistingRecordsAreKeptWithoutRewrite() throws IOException {
        Sandbox sandbox = standard("equal");
        sandbox.companies.put(company(9, "Beta", 1, -1, 40));
        sandbox.players.rows.put(uuid("Alex"), new PlayerDao(uuid("Alex").toString(), "Alex",
                new ArrayList<>(List.of(new InvestmentDao(9, 1, 33.0))), new HashSet<>()));
        sandbox.signs.rows.put(4, SignDao.builder().id(4).companyId(9).location(new LocationDao("world", 1, 2, 3)).build());

        sandbox.run();

        assertEquals("DONE", sandbox.phase());
        assertEquals(1, sandbox.companies.saves);
        assertEquals(33.0, investment(sandbox.players.rows.get(uuid("Alex")), 9).getAverageBuyPrice());
        assertEquals(1, sandbox.signs.saves);
    }

    @Test
    void existingPlayerKeepsUnrelatedPositions() throws IOException {
        Sandbox sandbox = standard("unrelated");
        sandbox.players.rows.put(uuid("Steve"), new PlayerDao(uuid("Steve").toString(), "Steve",
                new ArrayList<>(List.of(new InvestmentDao(200, 8, 2.0))), new HashSet<>()));

        sandbox.run();

        PlayerDao steve = sandbox.players.rows.get(uuid("Steve"));
        assertEquals(3, steve.getInvestments().size());
        assertEquals(8, investment(steve, 200).getSharesAmount());
    }

    @Test
    void exampleStaysWhenThereAreNoLegacyCompanies() throws IOException {
        Sandbox sandbox = new Sandbox("no-legacy", "Companies: {}\n", null, null);
        sandbox.companies.put(exampleCompany("Renamed by admin"));

        sandbox.run();

        assertEquals("DONE", sandbox.phase());
        assertTrue(sandbox.companies.rows.containsKey(0));
        assertEquals(0, sandbox.mutations());
    }

    @Test
    void legacyCompanyZeroIsMigratedAsARegularRecord() throws IOException {
        Sandbox sandbox = new Sandbox("legacy-zero",
                "Companies:\n  '0':\n    Name: Legacy\n    Price: 2.0\n    Risk: 1\n    AvailableStocks: 5\n",
                "Players:\n  Steve:\n    Companies:\n      '0':\n        Amount: 2\n",
                "Signs:\n  '0':\n    CompanyId: 0\n    Location: world,1,2,3\n");

        sandbox.run();

        assertEquals("Legacy", sandbox.companies.rows.get(0).getName());
        assertEquals(2, investment(sandbox.players.rows.get(uuid("Steve")), 0).getSharesAmount());
        assertEquals(0, sandbox.signs.rows.get(0).getCompanyId());
        assertEquals(0, sandbox.companies.deletes);
        assertEquals(1L, sandbox.companies.nextId);
    }

    @Test
    void defaultExampleAgainstLegacyCompanyZeroIsAConflict() throws IOException {
        Sandbox sandbox = new Sandbox("zero-conflict",
                "Companies:\n  '0':\n    Name: Legacy\n    Price: 2.0\n    Risk: 1\n    AvailableStocks: 5\n", null, null);
        sandbox.companies.put(exampleCompany("Blocks Corp"));

        assertThrows(MigrationException.class, sandbox::run);

        assertEquals("Blocks Corp", sandbox.companies.rows.get(0).getName());
        assertEquals(0, sandbox.mutations());
    }

    @Test
    void absentSourcesAreCompletedWithoutArchives() throws IOException {
        Sandbox sandbox = new Sandbox("absent", null, null, null);

        sandbox.run();

        assertEquals("DONE", sandbox.phase());
        assertFalse(Files.exists(sandbox.dir.resolve("companies.yml.old")));
        assertEquals(0, sandbox.mutations());
    }

    @Test
    void restoreFromBackupAllowsMigratingAgain() throws IOException {
        Sandbox sandbox = standard("restore");
        sandbox.run();
        Map<String, String> first = sandbox.snapshot();

        for (String name : List.of("companies.yml", "players.yml", "signs.yml")) {
            Files.copy(sandbox.dir.resolve("migration/v110/backup/" + name), sandbox.dir.resolve(name));
            Files.delete(sandbox.dir.resolve(name + ".old"));
        }
        assertSourcesIntact(sandbox);
        Files.delete(sandbox.dir.resolve("migration/v110/state.json"));

        sandbox.run();

        assertEquals("DONE", sandbox.phase());
        assertEquals(first, sandbox.snapshot());
    }

    @Test
    void sourceChangedAfterBackupFailsClosedAndKeepsBackup() throws IOException {
        Sandbox sandbox = standard("changed");
        sandbox.companies.failSaveOf = 5;
        assertThrows(MigrationException.class, sandbox::run);
        sandbox.companies.clearFaults();
        sandbox.write("companies.yml", COMPANIES + "# edited\n");

        MigrationException failure = assertThrows(MigrationException.class, sandbox::run);

        assertTrue(failure.getMessage().contains("changed after backup"), failure.getMessage());
        assertEquals(COMPANIES, Files.readString(sandbox.dir.resolve("migration/v110/backup/companies.yml")));
    }

    @Test
    void tamperedBackupFailsClosed() throws IOException {
        Sandbox sandbox = standard("tampered");
        sandbox.companies.failSaveOf = 5;
        assertThrows(MigrationException.class, sandbox::run);
        sandbox.companies.clearFaults();
        Files.writeString(sandbox.dir.resolve("migration/v110/backup/players.yml"), "Players: {}\n");

        assertThrows(MigrationException.class, sandbox::run);

        assertEquals("APPLY", sandbox.phase());
    }

    @Test
    void archivedFileWithWrongHashFails() throws IOException {
        Sandbox sandbox = standard("archive-hash");
        sandbox.ops.failRenameOf = "players.yml";
        assertThrows(MigrationException.class, sandbox::run);
        sandbox.ops.clear();
        Files.writeString(sandbox.dir.resolve("companies.yml.old"), "other");

        MigrationException failure = assertThrows(MigrationException.class, sandbox::run);

        assertTrue(failure.getMessage().contains("does not match the manifest"), failure.getMessage());
        assertEquals("ARCHIVE", sandbox.phase());
    }

    @Test
    void missingSourceDuringArchiveFails() throws IOException {
        Sandbox sandbox = standard("archive-missing");
        sandbox.ops.failRenameOf = "companies.yml";
        assertThrows(MigrationException.class, sandbox::run);
        sandbox.ops.clear();
        Files.delete(sandbox.dir.resolve("companies.yml"));

        assertThrows(MigrationException.class, sandbox::run);

        assertEquals("ARCHIVE", sandbox.phase());
    }

    @Test
    void existingOldFileIsNotOverwritten() throws IOException {
        Sandbox sandbox = standard("old-exists");
        Files.writeString(sandbox.dir.resolve("signs.yml.old"), "kept");

        assertThrows(MigrationException.class, sandbox::run);

        assertEquals("kept", Files.readString(sandbox.dir.resolve("signs.yml.old")));
        assertEquals(SIGNS, Files.readString(sandbox.dir.resolve("signs.yml")));
    }

    @Test
    void corruptStateFailsClosedInsteadOfStartingOver() throws IOException {
        Sandbox sandbox = standard("corrupt-state");
        Files.createDirectories(sandbox.dir.resolve("migration/v110"));
        Files.writeString(sandbox.dir.resolve("migration/v110/state.json"), "{not json");

        assertThrows(MigrationException.class, sandbox::run);

        assertEquals(0, sandbox.mutations());
        assertFalse(Files.exists(sandbox.dir.resolve("migration/v110/backup")));
        assertSourcesIntact(sandbox);
    }

    @Test
    void duplicatedPlayerIdentityFailsClosed() throws IOException {
        Sandbox sandbox = standard("duplicate");
        sandbox.offline = name -> offlinePlayer("same");

        assertThrows(MigrationException.class, sandbox::run);

        assertEquals(0, sandbox.mutations());
    }

    @Test
    void stateRoundTripsThroughDisk() throws IOException {
        Path dir = Files.createDirectories(tempDirectory.resolve("state"));
        MigrationState state = MigrationState.load(dir.toFile(), MigrationFileOps.DEFAULT);
        state.advanceTo(MigrationState.Phase.VERIFY);
        state.recordFailure("reason");

        MigrationState loaded = MigrationState.load(dir.toFile(), MigrationFileOps.DEFAULT);

        assertEquals(MigrationState.Phase.VERIFY, loaded.getPhase());
        assertEquals("reason", loaded.getFailureReason());
        assertEquals("VERIFY", loaded.getFailurePhase());
        assertNotNull(loaded);
    }

    @Test
    void absentManifestEntryRejectsResidualBackup() throws IOException {
        Sandbox sandbox = new Sandbox("residual", null, null, null);
        Path backup = Files.createDirectories(sandbox.dir.resolve("migration/v110/backup"));
        Files.writeString(backup.resolve("companies.yml"), COMPANIES);
        Files.writeString(backup.resolve("manifest.sha256"),
                "-  companies.yml\n-  players.yml\n-  signs.yml\n");

        assertThrows(MigrationException.class, sandbox::run);
        assertEquals(0, sandbox.mutations());
        assertEquals("STAGE", sandbox.phase());
    }

    @TestFactory
    Stream<DynamicTest> resumedIdentityChangesNeverCreateAnotherPosition() {
        return Stream.of("APPLY", "VERIFY").map(phase -> DynamicTest.dynamicTest(phase, () -> {
            Sandbox sandbox = standard("identity-" + phase);
            if (phase.equals("APPLY")) {
                sandbox.players.failSaveOf = uuid("Alex");
            } else {
                sandbox.companies.hideDuringVerifyOf = 9;
            }
            assertThrows(MigrationException.class, sandbox::run);
            assertEquals(phase, sandbox.phase());
            assertTrue(sandbox.players.rows.containsKey(uuid("Steve")));
            Path identities = sandbox.dir.resolve("migration/v110/identities.txt");
            String before = Files.exists(identities) ? Files.readString(identities) : "";
            sandbox.players.clearFaults();
            sandbox.companies.clearFaults();
            sandbox.offline = name -> name.equals("Steve") ? offlinePlayer("changed-" + name) : offlinePlayer(name);
            int writes = sandbox.players.saves;

            assertThrows(MigrationException.class, sandbox::run);

            assertEquals(writes, sandbox.players.saves);
            assertFalse(sandbox.players.rows.containsKey(uuid("changed-Steve")));
            assertEquals(before, Files.readString(identities));
        }));
    }

    @TestFactory
    Stream<DynamicTest> resumeRequiresFrozenIdentities() {
        return Stream.of("APPLY", "VERIFY").map(phase -> DynamicTest.dynamicTest(phase, () -> {
            Sandbox sandbox = standard("missing-identities-" + phase);
            if (phase.equals("APPLY")) {
                sandbox.players.failSaveOf = uuid("Alex");
            } else {
                sandbox.companies.hideDuringVerifyOf = 9;
            }
            assertThrows(MigrationException.class, sandbox::run);
            assertEquals(phase, sandbox.phase());
            Files.deleteIfExists(sandbox.dir.resolve("migration/v110/identities.txt"));
            sandbox.players.clearFaults();
            sandbox.companies.clearFaults();
            int mutations = sandbox.mutations();

            assertThrows(MigrationException.class, sandbox::run);
            assertEquals(mutations, sandbox.mutations());
        }));
    }

    @Test
    void identitiesAreSortedAndWrittenAtomicallyBeforeApply() throws IOException {
        Sandbox sandbox = standard("identity-write");
        sandbox.ops.failWriteOf = "identities.txt";

        assertThrows(MigrationException.class, sandbox::run);
        assertEquals("STAGE", sandbox.phase());
        assertEquals(0, sandbox.mutations());
        sandbox.ops.clear();
        sandbox.run();
        assertEquals(uuid("Alex") + "  Alex\n" + uuid("Steve") + "  Steve\n",
                Files.readString(sandbox.dir.resolve("migration/v110/identities.txt")));
    }

    @TestFactory
    Stream<DynamicTest> customizedExampleIsNeverDeleted() {
        Map<String, Consumer<CompanyDao.CompanyDaoBuilder>> changes = new LinkedHashMap<>();
        changes.put("name", c -> c.name("Custom"));
        changes.put("owner", c -> c.ownerUuid(uuid("owner").toString()));
        changes.put("risk", c -> c.risk(4));
        changes.put("total", c -> c.totalShares(101));
        changes.put("available", c -> c.availableShares(98));
        changes.put("initial price", c -> c.initialSharePrice(1001));
        return changes.entrySet().stream().map(change -> DynamicTest.dynamicTest(change.getKey(), () -> {
            Sandbox sandbox = standard("example-" + change.getKey());
            CompanyDao.CompanyDaoBuilder example = CompanyDao.builder().id(0).name("Blocks Corp")
                    .risk(3).totalShares(100).availableShares(99).initialSharePrice(1000).currentSharePrice(1000);
            change.getValue().accept(example);
            sandbox.companies.put(example.build());

            assertThrows(MigrationException.class, sandbox::run);
            assertEquals(0, sandbox.mutations());
            assertTrue(sandbox.companies.rows.containsKey(0));
        }));
    }

    @Test
    void exampleCurrentPriceAndHistoryMayVary() throws IOException {
        Sandbox sandbox = standard("example-price");
        me.geraldr12.utils.SizedStack<me.geraldr12.data.dao.QuoteDao> history = new me.geraldr12.utils.SizedStack<>(500);
        history.push(new me.geraldr12.data.dao.QuoteDao());
        sandbox.companies.put(CompanyDao.builder().id(0).name("Blocks Corp").risk(3).totalShares(100)
                .availableShares(99).initialSharePrice(1000).currentSharePrice(777).historic(history).build());
        sandbox.run();
        assertEquals("DONE", sandbox.phase());
        assertFalse(sandbox.companies.rows.containsKey(0));
    }

    @TestFactory
    Stream<DynamicTest> equivalentNumericIdsFailDuringStage() {
        return Stream.of("company", "sign").map(kind -> DynamicTest.dynamicTest(kind, () -> {
            String companyYaml = kind.equals("company") ? COMPANIES + "  '05':\n    Name: Other\n    Price: 1\n    Risk: 1\n    AvailableStocks: 2\n" : COMPANIES;
            String signYaml = kind.equals("sign") ? SIGNS.replace("'4'", "'5'") + "  '05':\n    CompanyId: 5\n    Location: world,1,2,3\n" : SIGNS;
            Sandbox sandbox = new Sandbox("numeric-" + kind, companyYaml, PLAYERS, signYaml);

            assertThrows(MigrationException.class, sandbox::run);
            assertEquals("STAGE", sandbox.phase());
            assertEquals(0, sandbox.mutations());
        }));
    }

    @Test
    void maximumCompanyIdFailsDuringStage() throws IOException {
        Sandbox sandbox = new Sandbox("max-id", COMPANIES.replace("'5'", "'2147483647'"), null, null);
        assertThrows(MigrationException.class, sandbox::run);
        assertEquals("STAGE", sandbox.phase());
        assertEquals(0, sandbox.mutations());
    }

    private static final class FaultyOps implements MigrationFileOps {
        String failCopyOf;
        String failRenameOf;
        String failWriteOf;
        String crashAfterWriteOf;
        int failOnWriteNumber = 1;
        int writes;

        void clear() {
            failCopyOf = null;
            failRenameOf = null;
            failWriteOf = null;
            crashAfterWriteOf = null;
            failOnWriteNumber = 1;
            writes = 0;
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            if (source.getFileName().toString().equals(failCopyOf)) {
                throw new IOException("copy failed");
            }
            MigrationFileOps.DEFAULT.copy(source, target);
        }

        @Override
        public void rename(Path source, Path target) throws IOException {
            if (source.getFileName().toString().equals(failRenameOf)) {
                throw new IOException("rename failed");
            }
            MigrationFileOps.DEFAULT.rename(source, target);
        }

        @Override
        public void writeAtomically(Path target, byte[] content) throws IOException {
            String name = target.getFileName().toString();
            boolean counted = name.equals(failWriteOf) || name.equals(crashAfterWriteOf);
            if (counted) {
                writes++;
            }
            if (name.equals(failWriteOf) && writes == failOnWriteNumber) {
                throw new IOException("write failed");
            }
            MigrationFileOps.DEFAULT.writeAtomically(target, content);
            if (name.equals(crashAfterWriteOf) && writes == failOnWriteNumber) {
                crashAfterWriteOf = null;
                failWriteOf = "state.json";
                failOnWriteNumber = Integer.MAX_VALUE;
                throw new SimulatedCrash();
            }
        }
    }

    private static final class SimulatedCrash extends RuntimeException {
    }

    private static final class FakeCompanies implements CompanyDestination {
        final Map<Integer, CompanyDao> rows = new TreeMap<>();
        Long nextId;
        int saves;
        int deletes;
        int counterRaises;
        Integer failSaveOf;
        Integer throwSaveOf;
        Integer hideDuringVerifyOf;
        boolean failDelete;
        boolean failCounter;
        boolean hidden;

        void put(CompanyDao company) {
            rows.put(company.getId(), company);
        }

        void clearFaults() {
            failSaveOf = null;
            throwSaveOf = null;
            hideDuringVerifyOf = null;
            failDelete = false;
            failCounter = false;
            if (hidden) {
                hidden = false;
            }
        }

        @Override
        public Optional<CompanyDao> findById(int companyId) {
            if (hideDuringVerifyOf != null && hideDuringVerifyOf == companyId && counterRaises > 0) {
                return Optional.empty();
            }
            return Optional.ofNullable(rows.get(companyId));
        }

        @Override
        public boolean exists(int companyId) {
            return rows.containsKey(companyId);
        }

        @Override
        public boolean save(CompanyDao company) {
            if (failSaveOf != null && failSaveOf == company.getId()) {
                return false;
            }
            if (throwSaveOf != null && throwSaveOf == company.getId()) {
                throw new IllegalStateException("disk failure");
            }
            assertFalse(rows.containsKey(company.getId()), "must never overwrite");
            saves++;
            rows.put(company.getId(), company);
            return true;
        }

        @Override
        public boolean delete(int companyId) {
            if (failDelete) {
                return false;
            }
            deletes++;
            return rows.remove(companyId) != null;
        }

        @Override
        public void raiseNextIdTo(long target) {
            if (failCounter) {
                throw new MigrationException("counter failed", null);
            }
            counterRaises++;
            if (nextId == null || nextId < target) {
                nextId = target;
            }
        }
    }

    private static final class FakePlayers implements PlayerDestination {
        final Map<UUID, PlayerDao> rows = new TreeMap<>();
        int saves;
        UUID failSaveOf;
        UUID corruptAfterSave;

        void clearFaults() {
            failSaveOf = null;
            if (corruptAfterSave != null) {
                PlayerDao row = rows.get(corruptAfterSave);
                if (row != null) {
                    row.getInvestments().forEach(item -> {
                        if (item.getCompanyId() == 5) {
                            item.setSharesAmount(7);
                        }
                    });
                }
                corruptAfterSave = null;
            }
        }

        @Override
        public Optional<PlayerDao> findById(UUID playerId) {
            return Optional.ofNullable(rows.get(playerId));
        }

        @Override
        public boolean save(PlayerDao player) {
            UUID id = UUID.fromString(player.getUniqueId());
            if (id.equals(failSaveOf)) {
                return false;
            }
            saves++;
            rows.put(id, player);
            if (id.equals(corruptAfterSave)) {
                player.getInvestments().forEach(item -> {
                    if (item.getCompanyId() == 5) {
                        item.setSharesAmount(999);
                    }
                });
            }
            return true;
        }
    }

    private static final class FakeSigns implements SignDestination {
        final Map<Integer, SignDao> rows = new TreeMap<>();
        int saves;
        Integer failSaveOf;
        Integer hideAfterSaveOf;

        void clearFaults() {
            failSaveOf = null;
            hideAfterSaveOf = null;
        }

        @Override
        public Optional<SignDao> findById(int signId) {
            if (hideAfterSaveOf != null && hideAfterSaveOf == signId && rows.containsKey(signId)) {
                return Optional.empty();
            }
            return Optional.ofNullable(rows.get(signId));
        }

        @Override
        public boolean save(SignDao sign) {
            if (failSaveOf != null && failSaveOf == sign.getId()) {
                return false;
            }
            assertFalse(rows.containsKey(sign.getId()), "must never overwrite");
            saves++;
            rows.put(sign.getId(), sign);
            return true;
        }
    }
}
