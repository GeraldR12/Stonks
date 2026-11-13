package me.geraldr12.shadow;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowReloadTest {
    @Test
    void reloadUsesReplacementConfigurationAndStopsPreviousSchedule() throws Exception {
        MemoryConfiguration disabled = new MemoryConfiguration();
        MemoryConfiguration enabled = new MemoryConfiguration();
        enabled.set("Stonks.Shadow.Observation.Enabled", true);
        enabled.set("Stonks.Shadow.Observation.IntervalMinutes", 2);
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicLong delay = new AtomicLong();
        ShadowObservation observation = new ShadowObservation(disabled, Clock.systemUTC(),
                (task, initial, period) -> {
                    registrations.incrementAndGet();
                    delay.set(initial);
                    return cancellations::incrementAndGet;
                }, null, () -> new ShadowObservation.Sink() {
                    public boolean submit(java.time.Instant at, long cycle, String json) { return true; }
                    public void close() {}
                }, warning -> {});
        observation.register();
        assertEquals(0, registrations.get());
        var register = ShadowObservation.class.getMethod("register", org.bukkit.configuration.ConfigurationSection.class);
        register.invoke(observation, enabled);
        assertEquals(1, registrations.get());
        assertEquals(2400, delay.get());
        observation.close();
        register.invoke(observation, disabled);
        assertEquals(1, registrations.get());
        assertEquals(1, cancellations.get());
        observation.close();
    }
}
