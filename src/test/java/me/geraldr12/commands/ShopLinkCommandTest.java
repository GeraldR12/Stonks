package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.ShopLinksService;
import me.geraldr12.integration.QuickShopAdapter;
import me.geraldr12.integration.ShopLookup;
import me.geraldr12.utils.Messages;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopLinkCommandTest {
    @TempDir Path temp;

    private final Stonks plugin = mock(Stonks.class);
    private final Player player = mock(Player.class);
    private final CompaniesService companies = mock(CompaniesService.class);
    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final Location chest = new Location(null, 1, 64, 2);
    private ShopLinksService links;
    private FakeLookup lookup;
    private ShopLinkCommand command;

    static final class FakeLookup implements ShopLookup {
        boolean available = true;
        QuickShopAdapter.ShopInfo shop;
        Location at;

        @Override public boolean isAvailable() { return available; }
        @Override public Optional<QuickShopAdapter.ShopInfo> shopAt(Location location) {
            return shop != null && location != null && location.equals(at) ? Optional.of(shop) : Optional.empty();
        }
        @Override public Optional<QuickShopAdapter.ShopInfo> shopById(long id) {
            return shop != null && shop.id() == id ? Optional.of(shop) : Optional.empty();
        }
    }

    @BeforeEach
    void setUp() {
        Messages messages = mock(Messages.class);
        when(messages.getPluginPrefix()).thenReturn("");
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getCompaniesService()).thenReturn(companies);
        links = new ShopLinksService(temp.resolve("links.yml"));
        lookup = new FakeLookup();
        when(plugin.getShopLinksService()).thenReturn(links);
        when(plugin.getShopLookup()).thenReturn(lookup);
        when(player.getUniqueId()).thenReturn(me);
        when(player.hasPermission("stonks.command.company.shop")).thenReturn(true);
        command = new ShopLinkCommand(plugin);
    }

    private void lookAtShop(long id, UUID owner) {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(chest);
        when(player.getTargetBlockExact(6)).thenReturn(block);
        lookup.shop = new QuickShopAdapter.ShopInfo(id, owner, true);
        lookup.at = chest;
    }

    private void company(long id, UUID owner, boolean bankrupt) {
        CompanyDao company = CompanyDao.builder().id((int) id).name("Co" + id).currentSharePrice(40)
                .ownerUuid(owner == null ? null : owner.toString()).bankrupt(bankrupt).build();
        when(companies.getCompanyById(id)).thenReturn(company);
    }

    private String lastMessage() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Test
    void notLookingAtAShop() {
        company(3, me, false);
        when(player.getTargetBlockExact(6)).thenReturn(null);
        command.onCommand(player, new String[]{"link", "3"});
        assertEquals("Look at one of your chest shops (within 6 blocks).", lastMessage());
        assertTrue(links.linksOf(3).isEmpty());
    }

    @Test
    void shopOfAnotherOwner() {
        company(3, me, false);
        lookAtShop(42, other);
        command.onCommand(player, new String[]{"link", "3"});
        assertEquals("You don't own this chest shop.", lastMessage());
        assertTrue(links.companyOf(42).isEmpty());
    }

    @Test
    void companyOfAnotherOwner() {
        company(3, other, false);
        lookAtShop(42, me);
        command.onCommand(player, new String[]{"link", "3"});
        assertEquals("You don't own that company.", lastMessage());
        assertTrue(links.companyOf(42).isEmpty());
    }

    @Test
    void missingOrBankruptCompany() {
        lookAtShop(42, me);
        command.onCommand(player, new String[]{"link", "9"});
        assertEquals("That company does not exist.", lastMessage());
        company(3, me, true);
        command.onCommand(player, new String[]{"link", "3"});
        assertEquals("That company is bankrupt.", lastMessage());
        assertTrue(links.companyOf(42).isEmpty());
    }

    @Test
    void invalidCompanyId() {
        lookAtShop(42, me);
        command.onCommand(player, new String[]{"link", "abc"});
        assertEquals("Usage: /invest company shop link <companyId>", lastMessage());
    }

    @Test
    void alreadyLinkedElsewhereIsRefused() {
        company(3, me, false);
        company(4, me, false);
        lookAtShop(42, me);
        links.link(42, 4, me);
        command.onCommand(player, new String[]{"link", "3"});
        assertEquals("This chest shop is already linked to Co4. Unlink it first.", lastMessage());
        assertEquals(4L, links.companyOf(42).orElseThrow());
    }

    @Test
    void quickShopUnavailable() {
        lookup.available = false;
        command.onCommand(player, new String[]{"link", "3"});
        assertEquals("Chest shop integration is not available on this server.", lastMessage());
        when(plugin.getShopLookup()).thenReturn(null);
        command.onCommand(player, new String[]{"list"});
        assertEquals("Chest shop integration is not available on this server.", lastMessage());
    }

    @Test
    void noPermission() {
        when(player.hasPermission("stonks.command.company.shop")).thenReturn(false);
        command.onCommand(player, new String[]{"list"});
        assertEquals("You don't have permission to do that.", lastMessage());
    }

    @Test
    void linkSucceedsAndIsIdempotent() {
        company(3, me, false);
        lookAtShop(42, me);
        command.onCommand(player, new String[]{"link", "3"});
        assertEquals("Chest shop #42 is now linked to Co3.", lastMessage());
        assertEquals(3L, links.companyOf(42).orElseThrow());
        command.onCommand(player, new String[]{"link", "3"});
        assertEquals("This chest shop is already linked to Co3.", lastMessage());
    }

    @Test
    void unlinkByOwnerAdminAndStranger() {
        company(3, me, false);
        lookAtShop(42, me);
        links.link(42, 3, me);
        command.onCommand(player, new String[]{"unlink"});
        assertEquals("Chest shop #42 is no longer linked.", lastMessage());
        assertTrue(links.companyOf(42).isEmpty());

        command.onCommand(player, new String[]{"unlink"});
        assertEquals("This chest shop is not linked to a company.", lastMessage());

        links.link(42, 3, other);
        lookup.shop = new QuickShopAdapter.ShopInfo(42, other, true);
        command.onCommand(player, new String[]{"unlink"});
        assertEquals("You don't own this chest shop.", lastMessage());
        assertEquals(3L, links.companyOf(42).orElseThrow());

        when(player.hasPermission("stonks.admin.reconcile")).thenReturn(true);
        command.onCommand(player, new String[]{"unlink"});
        assertEquals("Chest shop #42 is no longer linked.", lastMessage());
    }

    @Test
    void listShowsOnlyMyCompanies() {
        company(3, me, false);
        company(4, other, false);
        List<CompanyDao> all = List.of(companies.getCompanyById(3), companies.getCompanyById(4));
        when(companies.getAllCompanies()).thenReturn(all);
        command.onCommand(player, new String[]{"list"});
        assertEquals("You have no linked chest shops.", lastMessage());

        links.link(42, 3, me);
        links.link(43, 4, other);
        command.onCommand(player, new String[]{"list"});
        assertEquals("Shop #42 -> Co3 (#3)", lastMessage());
        verify(player, never()).sendMessage("Shop #43 -> Co4 (#4)");
    }

    @Test
    void tabComplete() {
        assertEquals(List.of("link", "unlink", "list"), command.onTabComplete(player, new String[]{""}));
        assertEquals(List.of("list"), command.onTabComplete(player, new String[]{"li"}).stream()
                .filter(s -> s.equals("list")).toList());
    }
}
