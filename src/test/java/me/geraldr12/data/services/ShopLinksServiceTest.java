package me.geraldr12.data.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShopLinksServiceTest {
    @TempDir Path temp;

    private Path file() {
        return temp.resolve("shops").resolve("links.yml");
    }

    @Test
    void linkThenLookupBothWays() {
        ShopLinksService links = new ShopLinksService(file());
        UUID owner = UUID.randomUUID();
        assertEquals(ShopLinksService.LinkResult.LINKED, links.link(7, 3, owner));
        assertEquals(3L, links.companyOf(7).orElseThrow());
        assertEquals(List.of(7L), links.linksOf(3).stream().map(ShopLinksService.ShopLink::shopId).toList());
        assertEquals(owner.toString(), links.linkOf(7).orElseThrow().ownerUuid());
    }

    @Test
    void linkIsIdempotentAndRefusesAnotherCompany() {
        ShopLinksService links = new ShopLinksService(file());
        UUID owner = UUID.randomUUID();
        links.link(7, 3, owner);
        assertEquals(ShopLinksService.LinkResult.ALREADY_LINKED, links.link(7, 3, owner));
        assertEquals(ShopLinksService.LinkResult.LINKED_ELSEWHERE, links.link(7, 4, owner));
        assertEquals(3L, links.companyOf(7).orElseThrow());
        assertEquals(1, links.linksOf(3).size());
    }

    @Test
    void unlinkIsIdempotent() {
        ShopLinksService links = new ShopLinksService(file());
        links.link(7, 3, UUID.randomUUID());
        assertTrue(links.unlink(7));
        assertFalse(links.unlink(7));
        assertTrue(links.companyOf(7).isEmpty());
    }

    @Test
    void survivesReload() {
        UUID owner = UUID.randomUUID();
        ShopLinksService first = new ShopLinksService(file());
        first.link(7, 3, owner);
        first.link(8, 3, owner);
        first.link(9, 5, owner);
        first.unlink(8);
        assertTrue(Files.isRegularFile(file()));

        ShopLinksService second = new ShopLinksService(file());
        assertEquals(3L, second.companyOf(7).orElseThrow());
        assertTrue(second.companyOf(8).isEmpty());
        assertEquals(5L, second.companyOf(9).orElseThrow());
        assertEquals(owner.toString(), second.linkOf(9).orElseThrow().ownerUuid());
    }

    @Test
    void removeCompanyDropsOnlyItsLinksAndPersists() {
        ShopLinksService links = new ShopLinksService(file());
        UUID owner = UUID.randomUUID();
        links.link(7, 3, owner);
        links.link(8, 3, owner);
        links.link(9, 5, owner);
        assertEquals(2, links.removeCompany(3));
        assertEquals(0, links.removeCompany(3));
        assertTrue(links.linksOf(3).isEmpty());
        assertEquals(5L, new ShopLinksService(file()).companyOf(9).orElseThrow());
        assertTrue(new ShopLinksService(file()).companyOf(7).isEmpty());
    }

    @Test
    void corruptFileStartsEmptyWithoutThrowing() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "links: [this is: not: valid");
        ShopLinksService links = new ShopLinksService(file());
        assertTrue(links.linksOf(3).isEmpty());
    }
}
