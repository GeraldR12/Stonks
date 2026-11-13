package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.dao.PlayerDao;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.data.sources.yml.implementations.CompaniesYml;
import me.geraldr12.data.sources.yml.implementations.PlayersYml;
import me.geraldr12.utils.SizedStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ShareMutationYamlRoundTripTest {
    @TempDir
    Path tempDir;

    @Test
    void companyRemoveRestoreRoundTripsThroughFreshRepositories() {
        CompaniesRepository initial = new CompaniesRepository(tempDir.toFile(), new CompaniesYml(tempDir.toFile()));
        assertTrue(initial.save(company(7, 100).toEntity()));
        CompaniesService removeService = new CompaniesService(mock(Stonks.class), initial);
        ShareMutationResult removed = removeService.removeSharesFromCompanyTyped(7, 5);
        assertEquals(ShareMutationOutcome.CONFIRMED, removed.getOutcome());
        CompaniesRepository reloaded = new CompaniesRepository(tempDir.toFile(), new CompaniesYml(tempDir.toFile()));
        assertEquals(95, reloaded.getById(7L).orElseThrow().getAvailableShares());
        ShareMutationResult restored = removeService.restoreSharesToCompanyTyped(7, removed.companyRemovalEvidence());
        assertEquals(ShareMutationOutcome.CONFIRMED, restored.getOutcome());
        CompaniesRepository finalRepo = new CompaniesRepository(tempDir.toFile(), new CompaniesYml(tempDir.toFile()));
        assertEquals(100, finalRepo.getById(7L).orElseThrow().getAvailableShares());
    }

    @Test
    void playerExistingHoldingRoundTripsAverageExactly() {
        UUID playerId = UUID.randomUUID();
        PlayersRepository initial = new PlayersRepository(new PlayersYml(tempDir.toFile()));
        assertTrue(initial.save(player(playerId, 10, 100.0).toEntity()));
        PlayersService service = new PlayersService(initial, mock(Stonks.class));
        ShareMutationResult added = service.addSharesToPlayerTyped(playerId, 42, 5, 200.0);
        assertEquals(ShareMutationOutcome.CONFIRMED, added.getOutcome());
        PlayersRepository reloaded = new PlayersRepository(new PlayersYml(tempDir.toFile()));
        InvestmentDao current = new PlayersService(reloaded, mock(Stonks.class))
                .getInvestmentInCompany(playerId, 42).orElseThrow();
        assertEquals(15, current.getSharesAmount());
        assertEquals(0, Double.compare(current.getAverageBuyPrice(), added.getAfterAveragePrice()));
        assertEquals(ShareMutationOutcome.CONFIRMED,
                service.restorePlayerSharesTyped(playerId, 42, added.playerAddEvidence()).getOutcome());
        InvestmentDao restored = new PlayersRepository(new PlayersYml(tempDir.toFile()))
                .getById(playerId).map(entity -> (PlayerDao) new PlayerDao().fromEntity(entity))
                .orElseThrow().getInvestments().get(0);
        assertEquals(10, restored.getSharesAmount());
        assertEquals(0, Double.compare(restored.getAverageBuyPrice(), 100.0));
    }

    @Test
    void absentPlayerHoldingRoundTripsAndRestoresToAbsent() {
        UUID playerId = UUID.randomUUID();
        PlayersRepository initial = new PlayersRepository(new PlayersYml(tempDir.toFile()));
        assertTrue(initial.save(player(playerId).toEntity()));
        PlayersService service = new PlayersService(initial, mock(Stonks.class));
        ShareMutationResult added = service.addSharesToPlayerTyped(playerId, 42, 5, 200.0);
        assertNull(added.getBeforeAveragePrice());
        PlayersRepository reloaded = new PlayersRepository(new PlayersYml(tempDir.toFile()));
        assertEquals(5, reloaded.getById(playerId).orElseThrow().getInvestments().get(0).getSharesAmount());
        assertEquals(ShareMutationOutcome.CONFIRMED,
                service.restorePlayerSharesTyped(playerId, 42, added.playerAddEvidence()).getOutcome());
        assertTrue(new PlayersRepository(new PlayersYml(tempDir.toFile())).getById(playerId).orElseThrow()
                .getInvestments().isEmpty());
    }

    private CompanyDao company(int id, int available) {
        return CompanyDao.builder().id(id).name("company").totalShares(100).availableShares(available)
                .currentSharePrice(10.0).historic(new SizedStack<>(10)).build();
    }

    private PlayerDao player(UUID id, int quantity, double price) {
        return PlayerDao.builder().uniqueId(id.toString()).name("player")
                .investments(new ArrayList<>(java.util.List.of(new InvestmentDao(42, quantity, price))))
                .blockedNotifications(new HashSet<>()).build();
    }

    private PlayerDao player(UUID id) {
        return PlayerDao.builder().uniqueId(id.toString()).name("player")
                .investments(new ArrayList<>()).blockedNotifications(new HashSet<>()).build();
    }
}
