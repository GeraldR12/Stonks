package me.geraldr12.shadow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.geraldr12.integration.CommerceSnapshot;
import me.geraldr12.integration.ProductionSnapshot;
import me.geraldr12.integration.TownFundamentalsSnapshotService;
import me.geraldr12.integration.TownSnapshot;
import me.geraldr12.integration.TownyAdapter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ShadowObservation implements AutoCloseable {
    interface Scheduler {
        Runnable repeat(Runnable task, long delay, long period);
    }

    interface Sink extends AutoCloseable {
        boolean submit(Instant observedAt, long cycle, String json);
        void close();
    }

    interface Source {
        List<String> townNames();
        TownSnapshot capture(String town, Instant at);
        CommerceSnapshot commerce(String town, Instant at, Duration window);
        ProductionSnapshot production(Instant at, Duration window);
    }

    private ConfigurationSection config;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Source source;
    private final Supplier<Sink> sinkFactory;
    private final Consumer<String> warning;
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private Runnable cancel;
    private Sink sink;
    private Duration window;
    private long intervalTicks;
    private long ticksUntilCycle;
    private long cycle;
    private long overlappingCycles;
    private long missingTowns;
    private long failedCaptures;
    private boolean invalidIntervalWarned;

    ShadowObservation(ConfigurationSection config, Clock clock, Scheduler scheduler, Source source,
                      Supplier<Sink> sinkFactory, Consumer<String> warning) {
        this.config = config;
        this.clock = clock;
        this.scheduler = scheduler;
        this.source = source;
        this.sinkFactory = sinkFactory;
        this.warning = warning;
    }

    public static ShadowObservation create(JavaPlugin plugin, TownFundamentalsSnapshotService service) {
        TownyAdapter towny = new TownyAdapter();
        return new ShadowObservation(plugin.getConfig(), Clock.systemUTC(), (task, delay, period) -> {
            BukkitTask scheduled = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delay, period);
            return scheduled::cancel;
        }, new Source() {
            public List<String> townNames() { return towny.townNames(); }
            public TownSnapshot capture(String town, Instant at) { return service.captureOnMainThread(town, at); }
            public CommerceSnapshot commerce(String town, Instant at, Duration window) {
                return service.commerce(town, at, window);
            }
            public ProductionSnapshot production(Instant at, Duration window) {
                return service.production(at, window);
            }
        }, () -> {
            ObservationWriter writer = new ObservationWriter(plugin.getDataFolder().toPath().resolve("shadow"),
                    plugin.getLogger()::warning);
            return new Sink() {
                public boolean submit(Instant at, long cycle, String line) { return writer.submit(at, cycle, line); }
                public void close() { writer.close(); }
            };
        }, plugin.getLogger()::warning);
    }

    public void register() { register(config); }
    public void register(ConfigurationSection config) {
        if (cancel != null && this.config == config) return;
        if (cancel == null && !config.getBoolean("Stonks.Shadow.Observation.Enabled", false)) return;
        ConfigurationSection previous = this.config;
        this.config = config;
        try {
            int minutes = config.getInt("Stonks.Shadow.Observation.IntervalMinutes", 10);
            if (minutes < 1) {
                if (!invalidIntervalWarned) {
                    invalidIntervalWarned = true;
                    warning.accept("Stonks.Shadow.Observation.IntervalMinutes must be at least 1; observation disabled.");
                }
                return;
            }
            window = Duration.ofMinutes(minutes);
            intervalTicks = minutes * 1200L;
            ticksUntilCycle = 0;
            sink = sinkFactory.get();
            if (cancel != null) {
                cancel.run();
                cancel = null;
            }
            try {
                cancel = scheduler.repeat(this::tick, intervalTicks, 1);
            } catch (RuntimeException exception) {
                sink.close();
                sink = null;
                throw exception;
            }
        } catch (Exception exception) {
            this.config = previous;
            if (sink != null) {
                sink.close();
                sink = null;
            }
            throw exception;
        }
    }

    private void tick() {
        if (ticksUntilCycle == 0) {
            if (!pending.isEmpty()) {
                overlappingCycles++;
            } else {
                cycle++;
                try {
                    pending.addAll(source.townNames());
                } catch (RuntimeException exception) {
                    failedCaptures++;
                }
            }
            ticksUntilCycle = intervalTicks;
        }
        ticksUntilCycle--;
        for (int count = 0; count < 25 && !pending.isEmpty(); count++) {
            String townName = pending.removeFirst();
            Instant at = clock.instant();
            try {
                TownSnapshot town = source.capture(townName, at);
                if (town == null) {
                    missingTowns++;
                    continue;
                }
                CommerceSnapshot commerce = source.commerce(townName, at, window);
                ProductionSnapshot production = source.production(at, window);
                sink.submit(at, cycle, line(town, commerce, production, at));
            } catch (RuntimeException | JsonProcessingException exception) {
                failedCaptures++;
            }
        }
    }

    private String line(TownSnapshot town, CommerceSnapshot commerce, ProductionSnapshot production, Instant at)
            throws JsonProcessingException {
        ObjectNode root = json.createObjectNode();
        root.put("schema", 1);
        root.put("observedAt", at.toString());
        root.put("townId", town.townId().toString());
        root.put("name", town.name());
        root.put("nation", town.nation().orElse(null));
        root.put("residentCount", town.residentCount());
        root.put("claimedLand", town.claimedLand());
        put(root, "treasury", town.treasury());
        ObjectNode activity = root.putObject("commerce");
        activity.put("status", commerce.status().name());
        put(activity, "transactionCount", commerce.transactionCount());
        put(activity, "quantity", commerce.quantity());
        put(activity, "monetaryVolume", commerce.monetaryVolume());
        put(activity, "shopCount", commerce.shopCount());
        activity.put("window", commerce.window().toString());
        root.putObject("production").put("status", production.status().name());
        return json.writeValueAsString(root);
    }

    private static void put(ObjectNode node, String name, TownSnapshot.OptionalDoubleValue value) {
        if (value.available()) node.put(name, value.value());
        else node.putNull(name);
    }

    private static void put(ObjectNode node, String name, CommerceSnapshot.OptionalLongValue value) {
        if (value.available()) node.put(name, value.value());
        else node.putNull(name);
    }

    public long overlappingCycles() { return overlappingCycles; }
    public long missingTowns() { return missingTowns; }
    public long failedCaptures() { return failedCaptures; }

    @Override
    public void close() {
        if (cancel != null) {
            cancel.run();
            cancel = null;
        }
        pending.clear();
        if (sink != null) {
            sink.close();
            sink = null;
        }
    }
}
