package me.geraldr12.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.geraldr12.integration.*;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ShadowObservationTest {
    private static final Instant NOW = Instant.parse("2026-11-09T23:59:00Z");
    @TempDir Path directory;

    @Test
    void disabledDoesNotScheduleCreateDirectoryOrOpenWriter() {
        Harness h = new Harness(false, 10, 2);
        h.observation.register();
        h.observation.close();
        assertEquals(0, h.scheduler.registrations);
        assertEquals(0, h.opens.get());
        assertFalse(Files.exists(directory.resolve("shadow")));
    }

    @Test
    void absentEnabledDefaultsToDisabled() {
        Harness h = new Harness(false, 10, 1);
        h.config.set("Stonks.Shadow.Observation.Enabled", null);
        h.observation.register();
        assertEquals(0, h.scheduler.registrations);
        assertEquals(0, h.opens.get());
    }

    @Test
    void invalidIntervalDisablesAndWarnsOnlyOnce() {
        Harness h = new Harness(true, 0, 1);
        h.observation.register();
        h.observation.register();
        assertEquals(1, h.warnings.size());
        assertEquals(0, h.scheduler.registrations);
        assertEquals(0, h.opens.get());
    }

    @Test
    void missingIntervalDefaultsToTenMinutes() {
        Harness h = new Harness(true, 1, 1);
        h.config.set("Stonks.Shadow.Observation.IntervalMinutes", null);
        h.observation.register();
        assertEquals(12000, h.scheduler.delay);
        h.observation.close();
    }

    @Test
    void registerTwiceKeepsExactlyOneSchedule() {
        Harness h = new Harness(true, 1, 1);
        h.observation.register();
        h.observation.register();
        assertEquals(1, h.scheduler.registrations);
        assertEquals(1, h.opens.get());
        h.observation.close();
        assertTrue(h.scheduler.cancelled);
        assertEquals(1, h.sink.closes);
        h.observation.close();
        assertEquals(1, h.sink.closes);
    }

    @Test
    void processesNoMoreThanTwentyFiveTownsPerTick() {
        Harness h = new Harness(true, 1, 61);
        h.observation.register();
        h.scheduler.tick();
        assertEquals(25, h.sink.lines.size());
        h.scheduler.tick();
        assertEquals(50, h.sink.lines.size());
        h.scheduler.tick();
        assertEquals(61, h.sink.lines.size());
        assertEquals(Duration.ofMinutes(1), h.source.window);
        h.observation.close();
    }

    @Test
    void overlappingCycleIsSkippedAndCounted() {
        Harness h = new Harness(true, 1, 30001);
        h.observation.register();
        for (int tick = 0; tick <= 1200; tick++) h.scheduler.tick();
        assertEquals(1, h.observation.overlappingCycles());
        assertEquals(1, h.source.enumerations);
        assertEquals(30001, h.sink.lines.size());
        h.observation.close();
    }

    @Test
    void missingTownAndCaptureFailureDoNotStopCycle() {
        Harness h = new Harness(true, 1, 3);
        h.source.missing = "town0";
        h.source.broken = "town1";
        h.observation.register();
        assertDoesNotThrow(h.scheduler::tick);
        assertEquals(1, h.sink.lines.size());
        assertEquals(1, h.observation.missingTowns());
        assertEquals(1, h.observation.failedCaptures());
        h.observation.close();
    }

    @Test
    void unavailableTownyProducesNoLinesOrFiles() {
        Harness h = new Harness(true, 1, 0);
        h.observation.register();
        assertDoesNotThrow(h.scheduler::tick);
        h.observation.close();
        assertTrue(h.sink.lines.isEmpty());
        assertFalse(Files.exists(directory.resolve("shadow")));
    }

    @Test
    void writtenJsonEscapesNamesAndKeepsUnavailableValuesNull() throws Exception {
        Harness h = new Harness(true, 1, 1);
        h.source.name = "city\"\\\nname";
        ObservationWriter writer = new ObservationWriter(directory.resolve("shadow"), h.warnings::add);
        h.observation = h.create(() -> new ShadowObservation.Sink() {
            public boolean submit(Instant at, long cycle, String json) { return writer.submit(at, cycle, json); }
            public void close() { writer.close(); }
        });
        h.observation.register();
        h.scheduler.tick();
        h.observation.close();
        List<String> lines = Files.readAllLines(directory.resolve("shadow/observations-2026-11-09.jsonl"));
        assertEquals(1, lines.size());
        JsonNode json = new ObjectMapper().readTree(lines.get(0));
        assertEquals(1, json.get("schema").intValue());
        assertEquals(NOW.toString(), json.get("observedAt").textValue());
        assertEquals(h.source.name, json.get("name").textValue());
        assertEquals(h.source.id.toString(), json.get("townId").textValue());
        assertTrue(json.get("nation").isNull());
        assertTrue(json.get("treasury").isNull());
        assertEquals(2, json.get("residentCount").intValue());
        assertEquals(3, json.get("claimedLand").intValue());
        JsonNode commerce = json.get("commerce");
        for (String field : List.of("transactionCount", "quantity", "monetaryVolume", "shopCount")) {
            assertTrue(commerce.get(field).isNull(), field);
        }
        assertEquals("PT1M", commerce.get("window").textValue());
        assertEquals("DATA_UNAVAILABLE", commerce.get("status").textValue());
        assertEquals("PLUGIN_MISSING", json.get("production").get("status").textValue());
    }

    @Test
    void verifiedZeroAndPositiveValuesAreNotDiscarded() throws Exception {
        Harness h = new Harness(true, 1, 1);
        h.source.available = true;
        h.observation.register();
        h.scheduler.tick();
        JsonNode json = new ObjectMapper().readTree(h.sink.lines.get(0));
        assertEquals("nation", json.get("nation").textValue());
        assertEquals(0.0, json.get("treasury").doubleValue());
        assertEquals(0, json.get("commerce").get("transactionCount").longValue());
        assertEquals(12, json.get("commerce").get("quantity").longValue());
        assertEquals(5.5, json.get("commerce").get("monetaryVolume").doubleValue());
        assertEquals(2, json.get("commerce").get("shopCount").longValue());
        h.observation.close();
    }

    @Test
    void usesInjectedClockAndDoesNotRepeatBeforeInterval() throws Exception {
        Harness h = new Harness(true, 1, 1);
        h.observation.register();
        h.scheduler.tick();
        for (int tick = 0; tick < 1199; tick++) h.scheduler.tick();
        assertEquals(1, h.source.enumerations);
        h.scheduler.tick();
        assertEquals(2, h.source.enumerations);
        assertEquals(NOW, h.source.observedAt);
        h.observation.close();
    }

    @Test
    void sourcesHaveNoEconomicDependencies() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/me/geraldr12/shadow"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                for (String forbidden : List.of("Economy", "CompaniesService", "PlayersService", "CompanyDao",
                        "MarketTransaction", "StocksRandomizer", "InterestRateScheduler")) {
                    assertFalse(text.contains(forbidden), file + ": " + forbidden);
                }
            }
        }
    }

    private class Harness {
        final MemoryConfiguration config = new MemoryConfiguration();
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeSource source = new FakeSource();
        final FakeSink sink = new FakeSink();
        final AtomicInteger opens = new AtomicInteger();
        final List<String> warnings = new ArrayList<>();
        ShadowObservation observation;
        Harness(boolean enabled, int minutes, int towns) {
            config.set("Stonks.Shadow.Observation.Enabled", enabled);
            config.set("Stonks.Shadow.Observation.IntervalMinutes", minutes);
            for (int i = 0; i < towns; i++) source.names.add("town" + i);
            observation = create(() -> { opens.incrementAndGet(); return sink; });
        }
        ShadowObservation create(java.util.function.Supplier<ShadowObservation.Sink> factory) {
            return new ShadowObservation(config, Clock.fixed(NOW, ZoneOffset.UTC), scheduler, source,
                    factory, warnings::add);
        }
    }

    private static class FakeScheduler implements ShadowObservation.Scheduler {
        Runnable task;
        int registrations;
        long delay;
        boolean cancelled;
        public Runnable repeat(Runnable task, long delay, long period) {
            assertEquals(1, period);
            this.task = task;
            this.delay = delay;
            registrations++;
            return () -> cancelled = true;
        }
        void tick() { if (!cancelled) task.run(); }
    }

    private static class FakeSink implements ShadowObservation.Sink {
        final List<String> lines = new ArrayList<>();
        int closes;
        public boolean submit(Instant at, long cycle, String json) { lines.add(json); return true; }
        public void close() { closes++; }
    }

    private static class FakeSource implements ShadowObservation.Source {
        final List<String> names = new ArrayList<>();
        final UUID id = UUID.randomUUID();
        String missing;
        String broken;
        String name = "town";
        boolean available;
        int enumerations;
        Duration window;
        Instant observedAt;
        public List<String> townNames() { enumerations++; return names; }
        public TownSnapshot capture(String town, Instant at) {
            observedAt = at;
            if (town.equals(missing)) return null;
            if (town.equals(broken)) throw new IllegalStateException("capture failed");
            return new TownSnapshot(id, name, available ? Optional.of("nation") : Optional.empty(), 2, 3,
                    available ? new TownSnapshot.OptionalDoubleValue(true, 0) : TownSnapshot.OptionalDoubleValue.unavailable(),
                    at, IntegrationStatus.AVAILABLE);
        }
        public CommerceSnapshot commerce(String town, Instant at, Duration window) {
            this.window = window;
            if (!available) return CommerceSnapshot.unavailable(at, window);
            return new CommerceSnapshot(new CommerceSnapshot.OptionalLongValue(true, 0),
                    new CommerceSnapshot.OptionalLongValue(true, 12), new TownSnapshot.OptionalDoubleValue(true, 5.5),
                    new CommerceSnapshot.OptionalLongValue(true, 2), at, window, IntegrationStatus.AVAILABLE);
        }
        public ProductionSnapshot production(Instant at, Duration window) {
            return new ProductionSnapshot(ProductionSnapshot.OptionalLongValue.unavailable(),
                    TownSnapshot.OptionalDoubleValue.unavailable(), at, window, IntegrationStatus.PLUGIN_MISSING);
        }
    }
}
