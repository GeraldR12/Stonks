package me.geraldr12.commands;

import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.SizedStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateDuplicateNameTest {
    @TempDir Path temp;

    @AfterEach
    void tearDown() {
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        MarketTransactionLocks.revoke();
    }

    @Test
    void duplicateIncludingBankruptCompanyHasNoFinancialEffects() {
        FinancialCommandFixture fixture = new FinancialCommandFixture(temp, true);
        CompaniesService companies = companies(fixture, "  BotCorp  ", true);
        when(fixture.plugin.getCompaniesService()).thenReturn(companies);
        clearInvocations(fixture.economy, fixture.players, fixture.journal);

        assertTrue(new CreateCommand(fixture.plugin).onCommand(fixture.player,
                new String[]{" bOtCoRp ", "1", "10", "2.0"}));

        verify(fixture.player).sendMessage("A company with that name already exists.");
        verify(companies, never()).reserveCompanyId(any());
        verify(companies, never()).saveReservedCompany(any());
        verify(fixture.journal, never()).prepare(any());
        verifyNoInteractions(fixture.economy, fixture.players);
        assertTrue(fixture.journal.getAllPendingOrReconciliationRecords().isEmpty());
    }

    @Test
    void newNameContinuesThroughCreation() {
        FinancialCommandFixture fixture = new FinancialCommandFixture(temp, true);
        CompaniesService companies = companies(fixture, "BotCorp", false);
        when(fixture.plugin.getCompaniesService()).thenReturn(companies);
        doReturn(22.0).when(companies).getCompanyCreationTax(10, 2.0, 1);
        doReturn(fixture.company).when(companies).reserveCompanyId(any());
        doReturn(true).when(companies).saveReservedCompany(fixture.company);
        doReturn(true).when(companies).removeSharesFromCompany(1, 10);

        fixture.command();

        verify(companies).reserveCompanyId(any());
        verify(fixture.journal).prepare(any());
        verify(fixture.economy).withdrawPlayer(fixture.player, 22.0);
        verify(fixture.player).sendMessage("Created Acme");
    }

    @Test
    void normalizationUsesRootLocaleAndIncludesActiveCompanies() {
        FinancialCommandFixture fixture = new FinancialCommandFixture(temp, true);
        CompaniesService companies = companies(fixture, " INVEST ", false);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(companies.companyNameExists("invest"));
            assertFalse(companies.companyNameExists("different"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private CompaniesService companies(FinancialCommandFixture fixture, String name, boolean bankrupt) {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getAllIds()).thenReturn(List.of(5L));
        when(repository.getById(5L)).thenReturn(Optional.of(CompanyDao.builder()
                .id(5).name(name).bankrupt(bankrupt).historic(new SizedStack<>(10)).build().toEntity()));
        return spy(new CompaniesService(fixture.plugin, repository));
    }
}
