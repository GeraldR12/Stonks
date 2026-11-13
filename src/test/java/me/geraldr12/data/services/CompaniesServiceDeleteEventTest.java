package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.events.CompanyDeleteEvent;
import me.geraldr12.utils.SizedStack;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompaniesServiceDeleteEventTest {

    @Test
    void mainThreadDeletionDispatchesSynchronousEventAfterPersistence() {
        CompaniesRepository repository = repositoryWithCompany();
        when(repository.delete(7L)).thenReturn(true);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);
        PluginManager manager = mock(PluginManager.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);

            assertTrue(service.deleteCompany(7L));

            ArgumentCaptor<CompanyDeleteEvent> event = ArgumentCaptor.forClass(CompanyDeleteEvent.class);
            var order = inOrder(repository, manager);
            order.verify(repository).delete(7L);
            order.verify(manager).callEvent(event.capture());
            assertFalse(event.getValue().isAsynchronous());
            assertEquals(7, event.getValue().getCompany().getId());
            verify(repository, times(1)).delete(7L);
        }
    }

    @Test
    void offMainThreadDeletionDispatchesAsynchronousEvent() {
        CompaniesRepository repository = repositoryWithCompany();
        when(repository.delete(7L)).thenReturn(true);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);
        PluginManager manager = mock(PluginManager.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);

            assertTrue(service.deleteCompany(7L));

            ArgumentCaptor<CompanyDeleteEvent> event = ArgumentCaptor.forClass(CompanyDeleteEvent.class);
            verify(manager).callEvent(event.capture());
            assertTrue(event.getValue().isAsynchronous());
        }
    }

    @Test
    void dispatchFailureIsLoggedWithoutFailingCommittedDeletion() {
        CompaniesRepository repository = repositoryWithCompany();
        when(repository.delete(7L)).thenReturn(true);
        Stonks plugin = mock(Stonks.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        CompaniesService service = new CompaniesService(plugin, repository);
        PluginManager manager = mock(PluginManager.class);
        IllegalStateException failure = new IllegalStateException("CompanyDeleteEvent may only be triggered asynchronously.");
        doThrow(failure).when(manager).callEvent(any(CompanyDeleteEvent.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);

            assertTrue(assertDoesNotThrow(() -> service.deleteCompany(7L)));

            verify(logger).log(eq(Level.WARNING), contains("7"), same(failure));
            verify(repository, times(1)).delete(7L);
            verify(manager, times(1)).callEvent(any(CompanyDeleteEvent.class));
        }
    }

    @Test
    void failedPersistenceDoesNotDispatchEvent() {
        CompaniesRepository repository = repositoryWithCompany();
        when(repository.delete(7L)).thenReturn(false);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            assertFalse(service.deleteCompany(7L));
            bukkit.verifyNoInteractions();
        }
    }

    @Test
    void missingCompanyDoesNotDeleteOrDispatchEvent() {
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getById(7L)).thenReturn(Optional.empty());
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            assertFalse(service.deleteCompany(7L));
            verify(repository, never()).delete(anyLong());
            bukkit.verifyNoInteractions();
        }
    }

    @Test
    void persistenceExceptionStillPropagatesWithoutDispatchingEvent() {
        CompaniesRepository repository = repositoryWithCompany();
        IllegalStateException failure = new IllegalStateException("persistence failed");
        when(repository.delete(7L)).thenThrow(failure);
        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.deleteCompany(7L)));
            bukkit.verifyNoInteractions();
        }
    }

    private CompaniesRepository repositoryWithCompany() {
        CompanyEntity company = CompanyDao.builder().id(7).name("Company")
                .historic(new SizedStack<>(10)).build().toEntity();
        CompaniesRepository repository = mock(CompaniesRepository.class);
        when(repository.getById(7L)).thenReturn(Optional.of(company));
        return repository;
    }
}
