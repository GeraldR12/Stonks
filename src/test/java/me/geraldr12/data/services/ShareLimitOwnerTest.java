package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.dao.PlayerDao;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The per-player share limit leaves out shares of companies the player owns. */
class ShareLimitOwnerTest {

    private final UUID owner = UUID.randomUUID();
    private final PlayersRepository repository = mock(PlayersRepository.class);
    private final Stonks plugin = mock(Stonks.class);
    private final CompaniesService companies = mock(CompaniesService.class);
    private final PlayersService service = new PlayersService(repository, plugin);

    /** Owner of company 24 with 3,893 of its shares, plus some shares of company 4 they don't own. */
    private void holdings(int ownCompanyShares, int otherShares) {
        when(plugin.getCompaniesService()).thenReturn(companies);
        CompanyDao own = mock(CompanyDao.class);
        when(own.getOwnerUuid()).thenReturn(owner.toString());
        CompanyDao other = mock(CompanyDao.class);
        when(other.getOwnerUuid()).thenReturn(UUID.randomUUID().toString());
        when(companies.getCompanyById(24)).thenReturn(own);
        when(companies.getCompanyById(4)).thenReturn(other);
        when(companies.getCompanyById(9)).thenReturn(other);
        List<InvestmentDao> investments = new ArrayList<>();
        investments.add(new InvestmentDao(24, ownCompanyShares, 40.0));
        if (otherShares > 0) investments.add(new InvestmentDao(4, otherShares, 13.0));
        PlayerDao dao = PlayerDao.builder().uniqueId(owner.toString()).name("x")
                .investments(investments).blockedNotifications(new HashSet<>()).build();
        when(repository.exists(owner)).thenReturn(true);
        when(repository.getById(owner)).thenReturn(Optional.of(dao.toEntity()));
        when(repository.save(any())).thenReturn(true);
    }

    @Test
    void ownCompanySharesDoNotCountTowardsTheLimit() {
        holdings(3893, 0);
        assertEquals(3893, service.getTotalPlayerSharesCount(owner));
        assertEquals(0, service.getSharesCountedTowardsLimit(owner));
        assertFalse(service.wouldExceedShareLimit(owner, 4, 100, 1000));
        assertEquals(ShareMutationOutcome.CONFIRMED,
                service.addSharesToPlayerTyped(owner, 4, 100, 13.0, 1000).getOutcome());
    }

    @Test
    void theLimitStillAppliesToOtherCompanies() {
        holdings(3893, 950);
        assertEquals(950, service.getSharesCountedTowardsLimit(owner));
        assertFalse(service.wouldExceedShareLimit(owner, 9, 50, 1000), "950 + 50 is exactly the limit");
        assertTrue(service.wouldExceedShareLimit(owner, 9, 51, 1000));
        assertEquals(ShareMutationOutcome.DEFINITE_REJECTED,
                service.addSharesToPlayerTyped(owner, 9, 51, 16.0, 1000).getOutcome());
    }

    @Test
    void anOwnerCanBuyBackTheirOwnCompanyAndADisabledLimitNeverBlocks() {
        holdings(3893, 950);
        assertFalse(service.wouldExceedShareLimit(owner, 24, 500, 1000));
        assertFalse(service.wouldExceedShareLimit(owner, 9, 100_000, 0));
        assertFalse(service.wouldExceedShareLimit(owner, 9, 100_000, -1));
    }

    @Test
    void withoutCompanyDataEverySharesCounts() {
        holdings(3893, 0);
        when(plugin.getCompaniesService()).thenReturn(null);
        assertEquals(3893, service.getSharesCountedTowardsLimit(owner));
        assertTrue(service.wouldExceedShareLimit(owner, 4, 1, 1000));
    }
}
