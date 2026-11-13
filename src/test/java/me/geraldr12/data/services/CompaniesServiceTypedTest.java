package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.utils.SizedStack;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompaniesServiceTypedTest {
    @Test
    void removeAndRestoreUseExactStateAndEvidence() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getById(7L)).thenReturn(Optional.of(company(7, 100)))
                .thenReturn(Optional.of(company(7, 95)));
        when(repository.save(any())).thenReturn(true);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);

        ShareMutationResult removed = service.removeSharesFromCompanyTyped(7, 5);
        assertEquals(ShareMutationOutcome.CONFIRMED, removed.getOutcome());
        assertEquals(95L, removed.getAfterQuantity());
        ConfirmedCompanyRemovalEvidence evidence = removed.companyRemovalEvidence();

        when(repository.getById(7L)).thenReturn(Optional.of(company(7, 95)));
        ShareMutationResult restored = service.restoreSharesToCompanyTyped(7, evidence);
        assertEquals(ShareMutationOutcome.CONFIRMED, restored.getOutcome());
        assertEquals(100L, restored.getAfterQuantity());
        verify(repository, times(2)).save(any());
    }

    @Test
    void invalidAndMismatchedStateDoNotWrite() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getById(7L)).thenReturn(Optional.of(company(7, 100)));
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);
        assertEquals(ShareMutationOutcome.DEFINITE_REJECTED, service.removeSharesFromCompanyTyped(7, 0).getOutcome());
        assertEquals(ShareMutationOutcome.DEFINITE_REJECTED, service.removeSharesFromCompanyTyped(7, 101).getOutcome());
        verify(repository, never()).save(any());
    }

    @Test
    void saveFalseAndExceptionAreAmbiguousWithoutRetry() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getById(7L)).thenReturn(Optional.of(company(7, 100)));
        when(repository.save(any())).thenReturn(false);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);
        assertEquals(ShareMutationOutcome.AMBIGUOUS, service.removeSharesFromCompanyTyped(7, 5).getOutcome());
        verify(repository, times(1)).save(any());
    }

    @Test
    void companyAddAndRestoreUseExactStateAndEvidence() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getById(7L)).thenReturn(Optional.of(company(7, 90)));
        when(repository.save(any())).thenReturn(true);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);

        ShareMutationResult added = service.addSharesToCompanyTyped(7, 5);
        assertEquals(ShareMutationOutcome.CONFIRMED, added.getOutcome());
        assertEquals(95L, added.getAfterQuantity());
        assertEquals(90L, added.getBeforeQuantity());
        ConfirmedCompanyAddEvidence evidence = added.companyAddEvidence();

        when(repository.getById(7L)).thenReturn(Optional.of(company(7, 95)));
        ShareMutationResult restored = service.removeConfirmedCompanyAddTyped(7, evidence);
        assertEquals(ShareMutationOutcome.CONFIRMED, restored.getOutcome());
        assertEquals(90L, restored.getAfterQuantity());
        verify(repository, times(2)).save(any());
    }

    @Test
    void companyAddExceedsLimitIsRejected() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getById(7L)).thenReturn(Optional.of(company(7, 98)));
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);
        // Total shares is 100 in the test method company()
        assertEquals(ShareMutationOutcome.DEFINITE_REJECTED, service.addSharesToCompanyTyped(7, 3).getOutcome());
        verify(repository, never()).save(any());
    }

    @Test
    void companyAddSaveFalseAndExceptionAreAmbiguous() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getById(7L)).thenReturn(Optional.of(company(7, 90)));
        when(repository.save(any())).thenReturn(false);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);
        assertEquals(ShareMutationOutcome.AMBIGUOUS, service.addSharesToCompanyTyped(7, 5).getOutcome());
    }

    private CompanyEntity company(int id, int available) {
        CompanyDao dao = CompanyDao.builder().id(id).name("x").totalShares(100).availableShares(available)
                .currentSharePrice(10.0).historic(new SizedStack<>(10)).build();
        return dao.toEntity();
    }
}
