package me.geraldr12.listeners;

import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.ShopLinksService;
import me.geraldr12.events.CompanyBankruptEvent;
import me.geraldr12.events.CompanyDeleteEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ShopLinksListenerTest {
    @TempDir Path temp;

    private ShopLinksService links;
    private ShopLinksListener listener;

    /** Same shape as QuickShop's Phase enum and ShopDeleteEvent (javap, 6.3.0.3). */
    public enum Phase { PRE, PRE_CANCELLABLE, MAIN, POST, RETRIEVE }

    public static class FakeShop {
        private final long id;
        public FakeShop(long id) { this.id = id; }
        public long getShopId() { return id; }
    }

    public static class FakeShopDeleteEvent {
        private final Phase phase;
        private final FakeShop shop;
        private final boolean cancelled;
        public FakeShopDeleteEvent(Phase phase, FakeShop shop, boolean cancelled) {
            this.phase = phase;
            this.shop = shop;
            this.cancelled = cancelled;
        }
        public Phase phase() { return phase; }
        public Optional<FakeShop> shop() { return Optional.ofNullable(shop); }
        public boolean isCancelled() { return cancelled; }
    }

    @BeforeEach
    void setUp() {
        links = new ShopLinksService(temp.resolve("links.yml"));
        listener = new ShopLinksListener(links);
        UUID owner = UUID.randomUUID();
        links.link(42, 3, owner);
        links.link(43, 3, owner);
        links.link(44, 5, owner);
    }

    @Test
    void companyDeleteAndBankruptcyDropTheirLinks() {
        listener.onCompanyDelete(new CompanyDeleteEvent(CompanyDao.builder().id(3).build()));
        assertTrue(links.linksOf(3).isEmpty());
        assertEquals(1, links.linksOf(5).size());
        listener.onCompanyBankrupt(new CompanyBankruptEvent(CompanyDao.builder().id(5).build()));
        assertTrue(links.linksOf(5).isEmpty());
    }

    @Test
    void shopDeleteUnlinksOnlyWhenItReallyHappens() {
        listener.onShopDelete(new FakeShopDeleteEvent(Phase.PRE, new FakeShop(42), false));
        listener.onShopDelete(new FakeShopDeleteEvent(Phase.MAIN, new FakeShop(42), true));
        assertTrue(links.companyOf(42).isPresent());

        listener.onShopDelete(new FakeShopDeleteEvent(Phase.MAIN, new FakeShop(42), false));
        assertTrue(links.companyOf(42).isEmpty());
        listener.onShopDelete(new FakeShopDeleteEvent(Phase.POST, new FakeShop(43), false));
        assertTrue(links.companyOf(43).isEmpty());
        assertTrue(links.companyOf(44).isPresent());
    }

    @Test
    void strangeEventsAreIgnored() {
        listener.onShopDelete(new Object());
        listener.onShopDelete(new FakeShopDeleteEvent(Phase.MAIN, null, false));
        assertEquals(2, links.linksOf(3).size());
    }
}
