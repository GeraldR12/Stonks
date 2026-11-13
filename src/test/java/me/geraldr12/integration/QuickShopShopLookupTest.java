package me.geraldr12.integration;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class QuickShopShopLookupTest {

    /** Same method names and shapes as QuickShop-Hikari 6.3.0.3 (checked with javap). */
    public static class FakeUser {
        private final UUID id;
        public FakeUser(UUID id) { this.id = id; }
        public Optional<UUID> getUniqueIdOptional() { return Optional.ofNullable(id); }
    }

    public static class FakeShop {
        private final long id;
        private final FakeUser owner;
        private final boolean selling;
        public FakeShop(long id, FakeUser owner, boolean selling) {
            this.id = id;
            this.owner = owner;
            this.selling = selling;
        }
        public long getShopId() { return id; }
        public FakeUser getOwner() { return owner; }
        public boolean isSelling() { return selling; }
    }

    public static class FakeShopManager {
        private final FakeShop shop;
        private final Location at;
        public FakeShopManager(FakeShop shop, Location at) {
            this.shop = shop;
            this.at = at;
        }
        public FakeShop getShopIncludeAttached(Location location) { return at.equals(location) ? shop : null; }
        public FakeShop getShop(long id) { return shop != null && shop.getShopId() == id ? shop : null; }
    }

    public static class BrokenShopManager {
        public Object getShopIncludeAttached(Location location) { throw new IllegalStateException("boom"); }
    }

    private final Location here = new Location(null, 1, 64, 2);
    private final Location elsewhere = new Location(null, 9, 64, 9);

    private QuickShopAdapter adapter(Object manager) {
        return new QuickShopAdapter(IntegrationStatus.AVAILABLE, () -> manager);
    }

    @Test
    void findsShopByLocationAndById() {
        UUID owner = UUID.randomUUID();
        QuickShopAdapter adapter = adapter(new FakeShopManager(new FakeShop(42, new FakeUser(owner), true), here));

        QuickShopAdapter.ShopInfo info = adapter.shopAt(here).orElseThrow();
        assertEquals(42, info.id());
        assertEquals(owner, info.owner());
        assertTrue(info.selling());
        assertEquals(42, adapter.shopById(42).orElseThrow().id());
    }

    @Test
    void noShopGivesEmpty() {
        QuickShopAdapter adapter = adapter(new FakeShopManager(new FakeShop(42, new FakeUser(UUID.randomUUID()), true), here));
        assertTrue(adapter.shopAt(elsewhere).isEmpty());
        assertTrue(adapter.shopById(7).isEmpty());
        assertTrue(adapter.shopAt(null).isEmpty());
    }

    @Test
    void shopWithoutOwnerUuidIsEmpty() {
        QuickShopAdapter adapter = adapter(new FakeShopManager(new FakeShop(42, new FakeUser(null), true), here));
        assertTrue(adapter.shopAt(here).isEmpty());
    }

    @Test
    void missingOrBrokenApiNeverThrows() {
        assertTrue(new QuickShopAdapter(IntegrationStatus.PLUGIN_MISSING, () -> {
            throw new IllegalStateException("not installed");
        }).shopAt(here).isEmpty());
        assertTrue(adapter(new Object()).shopAt(here).isEmpty());
        assertTrue(adapter(new Object()).shopById(1).isEmpty());
        assertTrue(adapter(new BrokenShopManager()).shopAt(here).isEmpty());
        assertTrue(new QuickShopAdapter(IntegrationStatus.AVAILABLE, () -> {
            throw new NoClassDefFoundError("com/ghostchu/quickshop/api/QuickShopAPI");
        }).shopAt(here).isEmpty());
    }

    @Test
    void unavailableAdapterDoesNotEvenAskTheApi() {
        QuickShopAdapter adapter = new QuickShopAdapter(IntegrationStatus.API_UNSUPPORTED, () -> {
            throw new AssertionError("must not be called");
        });
        assertTrue(adapter.shopAt(here).isEmpty());
        assertFalse(adapter.isAvailable());
    }
}
