package me.geraldr12.integration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class QuickShopAdapter implements StonksIntegrationAdapter, ShopLookup {
    private final IntegrationStatus status;
    private final String version;
    private final Supplier<Object> shopManager;

    /** A QuickShop chest shop as Stonks sees it. */
    public record ShopInfo(long id, UUID owner, boolean selling) {
    }

    @FunctionalInterface
    interface ApiValidator {
        void validate(Plugin plugin) throws ReflectiveOperationException;
    }

    public QuickShopAdapter() {
        this(Bukkit.getPluginManager().getPlugin("QuickShop-Hikari"),
                QuickShopAdapter::validateApi);
    }

    QuickShopAdapter(Plugin plugin, ApiValidator validator) {
        version = plugin == null ? "unknown" : plugin.getDescription().getVersion();
        status = resolveStatus(plugin, validator);
        shopManager = this::liveShopManager;
    }

    /** For tests: a fixed status and a stand-in for {@code QuickShopAPI.getInstance().getShopManager()}. */
    QuickShopAdapter(IntegrationStatus status, Supplier<Object> shopManager) {
        this.version = "test";
        this.status = status;
        this.shopManager = shopManager;
    }

    static IntegrationStatus resolveStatus(Plugin plugin, ApiValidator validator) {
        if (plugin == null) return IntegrationStatus.PLUGIN_MISSING;
        try {
            validator.validate(plugin);
            return IntegrationStatus.AVAILABLE;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return IntegrationStatus.API_UNSUPPORTED;
        }
    }

    private static void validateApi(Plugin plugin) throws ReflectiveOperationException {
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> eventType = Class.forName(
                "com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent", true, loader);
        Class<?> shopType = Class.forName(
                "com.ghostchu.quickshop.api.shop.Shop", true, loader);
        eventType.getMethod("getShop");
        eventType.getMethod("getAmount");
        shopType.getMethod("getLocation");
    }

    @Override
    public boolean isAvailable() {
        return status == IntegrationStatus.AVAILABLE;
    }

    /** The shop whose chest (or attached double chest) is at {@code location}. */
    @Override
    public Optional<ShopInfo> shopAt(Location location) {
        if (location == null) return Optional.empty();
        return lookup("getShopIncludeAttached", Location.class, location);
    }

    @Override
    public Optional<ShopInfo> shopById(long shopId) {
        return lookup("getShop", long.class, shopId);
    }

    private Optional<ShopInfo> lookup(String method, Class<?> argType, Object arg) {
        if (!isAvailable()) return Optional.empty();
        try {
            Object manager = shopManager.get();
            if (manager == null) return Optional.empty();
            return describe(call(manager, method, new Class<?>[]{argType}, arg));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<ShopInfo> describe(Object shop) throws ReflectiveOperationException {
        if (shop == null) return Optional.empty();
        long id = ((Number) call(shop, "getShopId", new Class<?>[0])).longValue();
        Object owner = call(shop, "getOwner", new Class<?>[0]);
        if (owner == null) return Optional.empty();
        Optional<?> ownerId = (Optional<?>) call(owner, "getUniqueIdOptional", new Class<?>[0]);
        if (ownerId == null || ownerId.isEmpty() || !(ownerId.get() instanceof UUID uuid)) return Optional.empty();
        boolean selling = Boolean.TRUE.equals(call(shop, "isSelling", new Class<?>[0]));
        return Optional.of(new ShopInfo(id, uuid, selling));
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, types);
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            // QuickShop's implementation classes may be package-private behind public interfaces.
            method.setAccessible(true);
            return method.invoke(target, args);
        }
    }

    private Object liveShopManager() {
        try {
            Class<?> api = Class.forName("com.ghostchu.quickshop.api.QuickShopAPI", true, classLoader());
            Object instance = api.getMethod("getInstance").invoke(null);
            return instance == null ? null : api.getMethod("getShopManager").invoke(instance);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public String id() {
        return "QuickShop-Hikari";
    }

    @Override
    public IntegrationStatus status() {
        return status;
    }

    @Override
    public Set<AdapterCapability> capabilities() {
        return status == IntegrationStatus.AVAILABLE ? Set.of(AdapterCapability.SHOP_ACTIVITY) : Set.of();
    }

    public ClassLoader classLoader() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("QuickShop-Hikari");
        if (plugin == null) throw new IllegalStateException("QuickShop is unavailable");
        return plugin.getClass().getClassLoader();
    }

    @Override
    public String version() {
        return version;
    }
}
