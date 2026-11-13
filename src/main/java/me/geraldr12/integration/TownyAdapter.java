package me.geraldr12.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class TownyAdapter implements StonksIntegrationAdapter {
    private final IntegrationStatus status;
    private final String version;
    private final Object api;
    private final Set<AdapterCapability> capabilities;
    private final BooleanSupplier primaryThread;

    public TownyAdapter() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Towny");
        IntegrationStatus resolvedStatus;
        String resolvedVersion;
        Object resolvedApi;
        Set<AdapterCapability> resolvedCapabilities;
        if (plugin == null) {
            resolvedStatus = IntegrationStatus.PLUGIN_MISSING;
            resolvedVersion = "unknown";
            resolvedApi = null;
            resolvedCapabilities = Set.of();
        } else {
            resolvedVersion = plugin.getDescription().getVersion();
            try {
                ClassLoader loader = plugin.getClass().getClassLoader();
                Class<?> apiType = Class.forName("com.palmergames.bukkit.towny.TownyAPI", true, loader);
                Class<?> townType = Class.forName(
                        "com.palmergames.bukkit.towny.object.Town", true, loader);
                validateRequiredMethods(apiType, townType);
                resolvedApi = apiType.getMethod("getInstance").invoke(null);
                EnumSet<AdapterCapability> found = EnumSet.of(
                        AdapterCapability.TOWN_IDENTITY,
                        AdapterCapability.TOWN_POPULATION,
                        AdapterCapability.TOWN_LAND,
                        AdapterCapability.TOWN_NATION);
                resolvedCapabilities = Set.copyOf(found);
                resolvedStatus = IntegrationStatus.AVAILABLE;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                resolvedStatus = IntegrationStatus.API_UNSUPPORTED;
                resolvedApi = null;
                resolvedCapabilities = Set.of();
            }
        }
        status = resolvedStatus;
        version = resolvedVersion;
        api = resolvedApi;
        capabilities = resolvedCapabilities;
        primaryThread = Bukkit::isPrimaryThread;
    }

    TownyAdapter(Object api, IntegrationStatus status, String version,
                 Set<AdapterCapability> capabilities) {
        this(api, status, version, capabilities, Bukkit::isPrimaryThread);
    }

    TownyAdapter(Object api, IntegrationStatus status, String version,
                 Set<AdapterCapability> capabilities, BooleanSupplier primaryThread) {
        this.api = api;
        this.status = status;
        this.version = version;
        this.capabilities = Set.copyOf(capabilities);
        this.primaryThread = primaryThread;
    }

    @FunctionalInterface
    interface RequiredMethodValidator {
        void validate() throws ReflectiveOperationException;
    }

    static IntegrationStatus resolveStatus(Object plugin, RequiredMethodValidator validator) {
        if (plugin == null) return IntegrationStatus.PLUGIN_MISSING;
        try {
            validator.validate();
            return IntegrationStatus.AVAILABLE;
        } catch (ReflectiveOperationException | LinkageError e) {
            return IntegrationStatus.API_UNSUPPORTED;
        }
    }

    static IntegrationStatus resolveStatus(Object plugin, boolean classesAndMethodsAvailable) {
        if (plugin == null) return IntegrationStatus.PLUGIN_MISSING;
        return classesAndMethodsAvailable
                ? IntegrationStatus.AVAILABLE : IntegrationStatus.API_UNSUPPORTED;
    }

    private static void validateRequiredMethods(Class<?> apiType, Class<?> townType)
            throws ReflectiveOperationException {
        apiType.getMethod("getInstance");
        apiType.getMethod("getTown", String.class);
        apiType.getMethod("getTown", org.bukkit.Location.class);
        townType.getMethod("getUUID");
        townType.getMethod("getName");
        townType.getMethod("getResidents");
        townType.getMethod("getTownBlocks");
        townType.getMethod("getNationOrNull");
    }

    @Override
    public String id() {
        return "Towny";
    }

    @Override
    public IntegrationStatus status() {
        return status;
    }

    @Override
    public Set<AdapterCapability> capabilities() {
        return capabilities;
    }

    @Override
    public String version() {
        return version;
    }

    /** Returns town names, or an empty list when off-thread, unavailable, or unable to read the API. */
    public java.util.List<String> townNames() {
        if (!primaryThread.getAsBoolean() || status != IntegrationStatus.AVAILABLE) return java.util.List.of();
        try {
            Object towns = api.getClass().getMethod("getTowns").invoke(api);
            if (!(towns instanceof java.util.Collection<?> collection)) return java.util.List.of();
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Object town : collection) {
                Object name = town.getClass().getMethod("getName").invoke(town);
                if (name instanceof String text && !text.isBlank()) names.add(text);
            }
            return java.util.List.copyOf(names);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return java.util.List.of();
        }
    }

    public Object townAt(org.bukkit.Location location) {
        if (!primaryThread.getAsBoolean() || status != IntegrationStatus.AVAILABLE) return null;
        return townAtResolved(api, location);
    }

    static Object townAtResolved(Object api, org.bukkit.Location location) {
        try {
            return api.getClass().getMethod("getTown", org.bukkit.Location.class).invoke(api, location);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public TownSnapshot capture(String townName, Instant observedAt) {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Towny snapshots must be captured on the main thread");
        }
        if (status != IntegrationStatus.AVAILABLE) {
            throw new IllegalStateException("Towny is not available");
        }
        try {
            Object town = api.getClass().getMethod("getTown", String.class).invoke(api, townName);
            return captureResolved(town, observedAt);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Unable to read Towny town", e);
        }
    }

    static TownSnapshot captureResolved(Object town, Instant observedAt) {
        if (town == null) return null;
        try {
            UUID id = (UUID) town.getClass().getMethod("getUUID").invoke(town);
            String name = String.valueOf(town.getClass().getMethod("getName").invoke(town));
            int residents = sizeOf(town, "getResidents");
            int land = sizeOf(town, "getTownBlocks");
            Optional<String> nation = Optional.empty();
            Object nationObject = invokeIfPresent(town, "getNationOrNull");
            if (nationObject != null) {
                nation = Optional.of(String.valueOf(nationObject.getClass().getMethod("getName").invoke(nationObject)));
            }
            return new TownSnapshot(id, name, nation, residents, land,
                    treasury(town), observedAt, IntegrationStatus.AVAILABLE);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Unable to read Towny town", e);
        }
    }

    private static TownSnapshot.OptionalDoubleValue treasury(Object town) throws ReflectiveOperationException {
        Object account = invokeIfPresent(town, "getAccount");
        if (account == null) return TownSnapshot.OptionalDoubleValue.unavailable();
        Object balance = invokeIfPresent(account, "getHoldingBalance");
        if (!(balance instanceof Number number)) return TownSnapshot.OptionalDoubleValue.unavailable();
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0.0) {
            return TownSnapshot.OptionalDoubleValue.unavailable();
        }
        return new TownSnapshot.OptionalDoubleValue(true, value);
    }

    private static int sizeOf(Object target, String methodName) throws ReflectiveOperationException {
        Object value = target.getClass().getMethod(methodName).invoke(target);
        if (!(value instanceof java.util.Collection<?> collection)) {
            throw new IllegalStateException("Towny returned unavailable " + methodName);
        }
        return collection.size();
    }

    private static Object invokeIfPresent(Object target, String methodName) throws ReflectiveOperationException {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
