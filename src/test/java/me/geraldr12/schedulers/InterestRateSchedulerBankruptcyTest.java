package me.geraldr12.schedulers;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.events.CompanyBankruptEvent;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.SizedStack;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterestRateSchedulerBankruptcyTest {

    @TempDir
    Path tempDir;

    @Test
    void removeBankruptFalsePersistsBankruptcyWithoutRemoval() throws Exception {
        TestContext context = context(false);
        CompanyDao company = bankruptCompany(7);
        Method processBankrupt = InterestRateScheduler.class
                .getDeclaredMethod("processBankruptCompany", CompanyDao.class);
        processBankrupt.setAccessible(true);

        invoke(processBankrupt, context.scheduler, company);

        verify(context.companies, times(1)).markCompanyBankrupt(7);
        verify(context.companies, never()).deleteCompany(anyLong());
        verify(context.players, never()).cleanUpInvestmentsForOnlinePlayers(any());
        verify(context.logger, never()).warning(anyString());
    }

    @Test
    void removeBankruptTrueWarnsOnceAndNeverRemoves() throws Exception {
        TestContext context = context(true);
        Method processBankrupt = InterestRateScheduler.class
                .getDeclaredMethod("processBankruptCompany", CompanyDao.class);
        processBankrupt.setAccessible(true);
        CompanyDao company = bankruptCompany(7);

        invoke(processBankrupt, context.scheduler, company);
        invoke(processBankrupt, context.scheduler, company);

        verify(context.companies, times(2)).markCompanyBankrupt(7);
        verify(context.companies, never()).deleteCompany(anyLong());
        verify(context.players, never()).cleanUpInvestmentsForOnlinePlayers(any());
        verify(context.logger, times(1)).warning(anyString());
    }

    @Test
    void failedBankruptcyPersistenceStopsBeforeCleanup() throws Exception {
        TestContext context = context(true);
        when(context.companies.markCompanyBankrupt(7)).thenReturn(false);
        Method processBankrupt = InterestRateScheduler.class
                .getDeclaredMethod("processBankruptCompany", CompanyDao.class);
        processBankrupt.setAccessible(true);

        assertThrows(IllegalStateException.class,
                () -> invoke(processBankrupt, context.scheduler, bankruptCompany(7)));

        verify(context.companies, never()).deleteCompany(anyLong());
        verify(context.players, never()).cleanUpInvestmentsForOnlinePlayers(any());
    }

    @Test
    void persistedBankruptcyKeepsCompanyAndHoldingsReferentiallyValid() {
        CompaniesRepository repository = new CompaniesRepository(
                tempDir.toFile(), new me.geraldr12.data.sources.yml.implementations.CompaniesYml(tempDir.toFile()));
        CompanyEntity entity = new CompanyEntity();
        entity.setId(7);
        entity.setName("bankrupt");
        entity.setCurrentSharePrice(10.0);
        entity.setHistoric(List.of());
        assertTrue(repository.save(entity));

        CompaniesService service = new CompaniesService(mock(Stonks.class), repository);
        assertTrue(service.markCompanyBankrupt(7));

        CompanyEntity persisted = repository.getById(7L).orElseThrow();
        assertEquals(0.0, persisted.getCurrentSharePrice());
        assertTrue(persisted.isBankrupt());
        assertTrue(repository.exists(7L));
    }

    @Test
    void bankruptcyEventIsSynchronous() {
        CompanyBankruptEvent event = new CompanyBankruptEvent(bankruptCompany(7));

        assertFalse(event.isAsynchronous());
        assertTrue(event.getCompany().isBankrupt());
        assertEquals(0.0, event.getCompany().getCurrentSharePrice());
    }

    private TestContext context(boolean removeBankrupt) {
        Stonks plugin = mock(Stonks.class);
        CompaniesService companies = mock(CompaniesService.class);
        PlayersService players = mock(PlayersService.class);
        SignsService signs = mock(SignsService.class);
        Messages messages = mock(Messages.class);
        FileConfiguration config = mock(FileConfiguration.class);
        Logger logger = mock(Logger.class);

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isJournalReady()).thenReturn(true);
        me.geraldr12.persistence.MarketTransactionJournal journal = mock(me.geraldr12.persistence.MarketTransactionJournal.class);
        when(plugin.getTransactionJournal()).thenReturn(journal);
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
        when(config.getBoolean("Stonks.StockCrash.RemoveBankrupt", false)).thenReturn(removeBankrupt);
        when(plugin.getLogger()).thenReturn(logger);
        when(companies.markCompanyBankrupt(anyLong())).thenReturn(true);
        when(companies.persistSchedulerSnapshot(any())).thenReturn(true);
        when(companies.getCompanyById(anyLong())).thenAnswer(invocation -> mutableCompany(invocation.getArgument(0, Long.class).intValue()));

        return new TestContext(
                plugin,
                new InterestRateScheduler(plugin, companies, signs, players, messages),
                companies, players, signs, logger);
    }

    private CompanyDao bankruptCompany(int id) {
        return CompanyDao.builder()
                .id(id)
                .name("bankrupt")
                .currentSharePrice(0.0)
                .bankrupt(true)
                .build();
    }

    private CompanyDao mutableCompany(int id) {
        return CompanyDao.builder()
                .id(id)
                .name("mutable")
                .risk(1)
                .initialSharePrice(10.0)
                .currentSharePrice(10.0)
                .bankrupt(false)
                .build();
    }

    private void invokeProcessCompany(TestContext context, CompanyDao company) throws Exception {
        Method processCompany = InterestRateScheduler.class
                .getDeclaredMethod("processCompany", CompanyDao.class);
        processCompany.setAccessible(true);
        invoke(processCompany, context.scheduler, company);
    }

    private void invoke(Method method, Object target, Object argument) throws Exception {
        try {
            method.invoke(target, argument);
        } catch (InvocationTargetException e) {
            throw (e.getCause() instanceof Exception exception)
                    ? exception
                    : e;
        }
    }

    private record TestContext(Stonks plugin, InterestRateScheduler scheduler, CompaniesService companies,
                               PlayersService players, SignsService signs, Logger logger) {
    }
    @Test
    void admittedCompanyUpdateCompletesAfterConcurrentRevocation() throws Exception {
        TestContext context = context(false);
        CompanyDao company = mutableCompany(7);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        when(context.companies.persistSchedulerSnapshot(any()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return true;
                });
        when(context.companies.updateCompanyHistoric(anyLong(), any())).thenReturn(true);

        Thread worker = new Thread(() -> {
            try {
                invokeProcessCompany(context, company);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        Thread revoker = null;
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS), () -> "worker failed: " + failure.get());
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            revoker = new Thread(MarketTransactionLocks::revoke);
            revoker.start();
            revoker.join(5000);
            assertFalse(revoker.isAlive());
            assertTrue(MarketTransactionLocks.tryAdmit(context.plugin.getTransactionJournal()) == null);
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(null, failure.get());
            verify(context.companies, times(1)).persistSchedulerSnapshot(any());
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            release.countDown();
            if (revoker != null) {
                revoker.join(5000);
            }
            worker.join(5000);
        }
    }

    @Test
    void companyLockCoversCompletePriceAndHistoryUpdate() throws Exception {
        TestContext context = context(false);
        CompanyDao company = mutableCompany(7);
        CountDownLatch priceEntered = new CountDownLatch(1);
        CountDownLatch releasePrice = new CountDownLatch(1);
        CountDownLatch competitorEntered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        when(context.companies.persistSchedulerSnapshot(any()))
                .thenAnswer(invocation -> {
                    priceEntered.countDown();
                    assertTrue(releasePrice.await(5, TimeUnit.SECONDS));
                    return true;
                });
        when(context.companies.updateCompanyHistoric(anyLong(), any())).thenReturn(true);

        Thread worker = new Thread(() -> {
            try {
                invokeProcessCompany(context, company);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        Thread competitor = new Thread(() -> {
            synchronized (MarketTransactionLocks.company(company.getId())) {
                competitorEntered.countDown();
            }
        });
        try {
            worker.start();
            assertTrue(priceEntered.await(5, TimeUnit.SECONDS));
            competitor.start();
            assertFalse(competitorEntered.await(200, TimeUnit.MILLISECONDS));
            releasePrice.countDown();
            worker.join(5000);
            competitor.join(5000);
            assertFalse(worker.isAlive());
            assertFalse(competitor.isAlive());
            assertEquals(null, failure.get());
            assertTrue(competitorEntered.getCount() == 0);
            verify(context.companies, times(1)).persistSchedulerSnapshot(any());
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            releasePrice.countDown();
            worker.join(5000);
            competitor.join(5000);
        }
    }

    @Test
    void runContinuesWithNextCompanyAfterOneCompanyFails() throws Exception {
        TestContext context = context(false);
        CompanyDao first = mutableCompany(7);
        CompanyDao second = mutableCompany(8);
        when(context.companies.persistSchedulerSnapshot(any()))
                .thenAnswer(invocation -> {
                    if (invocation.getArgument(0, CompanyDao.class).getId() == 7) {
                        throw new IllegalStateException("first company failed");
                    }
                    return true;
                });

        assertThrows(IllegalStateException.class, () -> invokeProcessCompany(context, first));
        invokeProcessCompany(context, second);

        verify(context.companies, times(2)).persistSchedulerSnapshot(any());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

    @Test
    void schedulerSnapshotSaveFailureReleasesAdmission() throws Exception {
        TestContext context = context(false);
        when(context.companies.persistSchedulerSnapshot(any())).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> invokeProcessCompany(context, mutableCompany(7)));

        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        verify(context.companies, times(1)).persistSchedulerSnapshot(any());
    }

    @Test
    void schedulerSnapshotPreservesAuthoritativeCompanyFields() throws Exception {
        TestContext context = context(false);
        CompanyDao authoritative = CompanyDao.builder()
                .id(7).name("owned").description("desc")
                .initialSharePrice(10.0).currentSharePrice(10.0)
                .risk(1).totalShares(100).availableShares(40)
                .ownerUuid("owner").historic(new SizedStack<>(500)).bankrupt(false).build();
        when(context.companies.getCompanyById(7)).thenReturn(authoritative);
        when(context.companies.persistSchedulerSnapshot(any())).thenAnswer(invocation -> {
            CompanyDao snapshot = invocation.getArgument(0, CompanyDao.class);
            assertEquals("owned", snapshot.getName());
            assertEquals("desc", snapshot.getDescription());
            assertEquals(100, snapshot.getTotalShares());
            assertEquals(40, snapshot.getAvailableShares());
            assertEquals("owner", snapshot.getOwnerUuid());
            assertEquals(1, snapshot.getHistoric().size());
            return true;
        });

        invokeProcessCompany(context, authoritative);

        verify(context.companies, times(1)).persistSchedulerSnapshot(any());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

    @Test
    void pendingLifecycleRejectsCompanyUpdateWithoutEffects() throws Exception {
        TestContext context = context(false);
        me.geraldr12.persistence.MarketTransactionJournal pendingJournal =
                mock(me.geraldr12.persistence.MarketTransactionJournal.class);
        when(context.plugin.getTransactionJournal()).thenReturn(pendingJournal);
        MarketTransactionLocks.initialize(pendingJournal);

        invokeProcessCompany(context, mutableCompany(7));

        verifyNoCompanyEffects(context);
    }

    @Test
    void closingLifecycleRejectsCompanyUpdateWithoutEffects() throws Exception {
        TestContext context = context(false);
        MarketTransactionLocks.revoke();

        invokeProcessCompany(context, mutableCompany(7));

        verifyNoCompanyEffects(context);
    }

    @Test
    void closedLifecycleRejectsCompanyUpdateWithoutEffects() throws Exception {
        TestContext context = context(false);
        MarketTransactionLocks.Admission admission =
                MarketTransactionLocks.tryAdmit(context.plugin.getTransactionJournal());
        assertTrue(admission != null);
        MarketTransactionLocks.revoke();
        admission.close();

        invokeProcessCompany(context, mutableCompany(7));

        verifyNoCompanyEffects(context);
    }

    private void verifyNoCompanyEffects(TestContext context) {
        verify(context.companies, never()).updateCompanySharesValue(anyLong(), anyDouble(), anyDouble());
        verify(context.companies, never()).updateCompanyHistoric(anyLong(), any());
        verify(context.companies, never()).markCompanyBankrupt(anyLong());
        verify(context.signs, never()).updateBukkitSignsByCompany(anyLong());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

    @Test
    void unreadySchedulerDoesNotPersistBankruptcy() throws Exception {
        TestContext context = context(false);
        when(context.plugin.isJournalReady()).thenReturn(false);
        Method processCompany = InterestRateScheduler.class
                .getDeclaredMethod("processCompany", CompanyDao.class);
        processCompany.setAccessible(true);

        invoke(processCompany, context.scheduler, bankruptCompany(7));

        verify(context.companies, never()).markCompanyBankrupt(anyLong());
        verify(context.companies, never()).updateCompanySharesValue(anyLong(), anyDouble(), anyDouble());
        verify(context.companies, never()).updateCompanyHistoric(anyLong(), any());
    }

    @Test
    void uninitializedLocksDoNotPersistBankruptcy() throws Exception {
        TestContext context = context(false);
        Field initialized = MarketTransactionLocks.class.getDeclaredField("initialized");
        Field journal = MarketTransactionLocks.class.getDeclaredField("journal");
        initialized.setAccessible(true);
        journal.setAccessible(true);
        boolean previousInitialized = initialized.getBoolean(null);
        Object previousJournal = journal.get(null);
        try {
            initialized.setBoolean(null, false);
            invokeProcessCompany(context, bankruptCompany(7));

            verify(context.companies, never()).markCompanyBankrupt(anyLong());
            verify(context.companies, never()).updateCompanySharesValue(anyLong(), anyDouble(), anyDouble());
            verify(context.companies, never()).updateCompanyHistoric(anyLong(), any());
        } finally {
            journal.set(null, previousJournal);
            initialized.setBoolean(null, previousInitialized);
        }
    }

    @Test
    void mismatchedJournalDoesNotPersistBankruptcy() throws Exception {
        TestContext context = context(false);
        when(context.plugin.getTransactionJournal()).thenReturn(mock(me.geraldr12.persistence.MarketTransactionJournal.class));
        Method processCompany = InterestRateScheduler.class
                .getDeclaredMethod("processCompany", CompanyDao.class);
        processCompany.setAccessible(true);

        invoke(processCompany, context.scheduler, bankruptCompany(7));

        verify(context.companies, never()).markCompanyBankrupt(anyLong());
    }

    @Test
    void readinessProbeFailureDoesNotPersistBankruptcy() throws Exception {
        TestContext context = context(false);
        when(context.plugin.isJournalReady()).thenThrow(new IllegalStateException("unavailable"));
        Method processCompany = InterestRateScheduler.class
                .getDeclaredMethod("processCompany", CompanyDao.class);
        processCompany.setAccessible(true);

        invoke(processCompany, context.scheduler, bankruptCompany(7));

        verify(context.companies, never()).markCompanyBankrupt(anyLong());
        verify(context.companies, never()).updateCompanySharesValue(anyLong(), anyDouble(), anyDouble());
        verify(context.companies, never()).updateCompanyHistoric(anyLong(), any());
    }
}
