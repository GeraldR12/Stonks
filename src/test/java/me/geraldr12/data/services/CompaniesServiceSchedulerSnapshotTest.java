package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.utils.SizedStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompaniesServiceSchedulerSnapshotTest {

    @Test
    void invalidSnapshotsDoNotReachRepository() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);

        assertFalse(service.persistSchedulerSnapshot(null));
        assertFalse(service.persistSchedulerSnapshot(snapshot(-1, 10.0)));
        assertFalse(service.persistSchedulerSnapshot(snapshot(0, Double.NaN)));
        assertFalse(service.persistSchedulerSnapshot(snapshot(0, Double.POSITIVE_INFINITY)));
        assertFalse(service.persistSchedulerSnapshot(snapshot(0, Double.NEGATIVE_INFINITY)));
        assertFalse(service.persistSchedulerSnapshot(snapshot(0, -0.01)));
        assertFalse(service.persistSchedulerSnapshot(CompanyDao.builder()
                .id(0).currentSharePrice(10.0).build()));

        verifyNoInteractions(repository);
    }

    @Test
    void zeroPriceBankruptcyForDefaultCompanyIsPersistedOnce() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.save(any())).thenReturn(true);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);

        assertTrue(service.persistSchedulerSnapshot(snapshot(0, 0.0)));

        verify(repository, times(1)).save(argThat(entity -> entity.getId() == 0
                && entity.getCurrentSharePrice() == 0.0));
    }

    private CompanyDao snapshot(int id, double price) {
        return CompanyDao.builder().id(id).currentSharePrice(price)
                .historic(new SizedStack<>(500)).build();
    }
}
