package me.geraldr12.shadow;

import me.geraldr12.integration.IntegrationStatus;
import me.geraldr12.integration.TownyAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ShadowContractTest {
    @Test
    void observationLifecycleExists() throws Exception {
        assertNotNull(Class.forName("me.geraldr12.shadow.ShadowObservation"));
    }

    @Test
    void townNamesUsesOptionalApiWithoutDowngradingStatus() throws Exception {
        TownyAdapter adapter = adapter(new Api(), true);
        assertEquals(List.of("A", "B"), TownyAdapter.class.getMethod("townNames").invoke(adapter));
        assertEquals(IntegrationStatus.AVAILABLE, adapter.status());
    }

    @Test
    void absentTownEnumerationReturnsEmptyWithoutDowngrading() throws Exception {
        TownyAdapter adapter = adapter(new Object(), true);
        assertEquals(List.of(), TownyAdapter.class.getMethod("townNames").invoke(adapter));
        assertEquals(IntegrationStatus.AVAILABLE, adapter.status());
    }

    @Test
    void failedEnumerationReturnsEmpty() throws Exception {
        assertEquals(List.of(), TownyAdapter.class.getMethod("townNames").invoke(adapter(new BrokenApi(), true)));
    }

    @Test
    void enumerationNeverCallsApiOffMainThread() throws Exception {
        Api api = new Api();
        assertEquals(List.of(), TownyAdapter.class.getMethod("townNames").invoke(adapter(api, false)));
        assertEquals(0, api.calls);
    }

    private static TownyAdapter adapter(Object api, boolean mainThread) throws Exception {
        Constructor<TownyAdapter> constructor = TownyAdapter.class.getDeclaredConstructor(
                Object.class, IntegrationStatus.class, String.class, Set.class, BooleanSupplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance(api, IntegrationStatus.AVAILABLE, "test", Set.of(),
                (BooleanSupplier) () -> mainThread);
    }

    public static class Api {
        int calls;
        public List<Town> getTowns() {
            calls++;
            return List.of(new Town("A"), new Town("B"));
        }
    }

    public record Town(String name) {
        public String getName() { return name; }
    }

    public static class BrokenApi {
        public List<Town> getTowns() { throw new IllegalStateException("unavailable"); }
    }
}
