package me.geraldr12.utils;

import me.geraldr12.Stonks;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketTransactionFinancialReadinessTest {

    @Test
    void rejectsUnavailableAndMismatchedState() throws Exception {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = new MarketTransactionJournal(temp());
        UUID playerId = UUID.randomUUID();

        assertFalse(MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, 7));

        when(plugin.isJournalReady()).thenReturn(true);
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
        assertTrue(MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, 7));

        MarketTransactionJournal differentJournal = new MarketTransactionJournal(temp());
        assertFalse(MarketTransactionLocks.isFinanciallyReady(plugin, differentJournal, playerId, 7));
    }

    @Test
    void unresolvedPlayerAndCompanyRecordsBlockReadiness() throws Exception {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        UUID playerId = UUID.randomUUID();
        when(plugin.isJournalReady()).thenReturn(true);
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setPlayerUuid(playerId.toString());
        record.setCompanyId(7L);
        when(journal.getUnresolvedRecords()).thenReturn(List.of(record));
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));

        org.junit.jupiter.api.Assertions.assertFalse(
                MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, 7));
    }

    @Test
    void unresolvedDifferentIdentityDoesNotBlockReadiness() throws Exception {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        UUID playerId = UUID.randomUUID();
        when(plugin.isJournalReady()).thenReturn(true);
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setPlayerUuid(UUID.randomUUID().toString());
        record.setCompanyId(8L);
        when(journal.getUnresolvedRecords()).thenReturn(List.of(record));
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));

        org.junit.jupiter.api.Assertions.assertTrue(
                MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, 7));
    }

    @Test
    void readinessCanBeObservedInsideCanonicalLocks() throws Exception {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        UUID playerId = UUID.randomUUID();
        long companyId = 7L;
        when(plugin.isJournalReady()).thenReturn(true);
        when(journal.getUnresolvedRecords()).thenReturn(List.of());
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
        AtomicBoolean observed = new AtomicBoolean();
        synchronized (MarketTransactionLocks.companyAllocation()) {
            synchronized (MarketTransactionLocks.company(companyId)) {
                synchronized (MarketTransactionLocks.player(playerId)) {
                    observed.set(Thread.holdsLock(MarketTransactionLocks.companyAllocation())
                            && Thread.holdsLock(MarketTransactionLocks.company(companyId))
                            && Thread.holdsLock(MarketTransactionLocks.player(playerId))
                            && MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, companyId));
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(observed.get());
    }

    @Test
    void readinessProbeFailureRejectsSafely() throws Exception {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        UUID playerId = UUID.randomUUID();
        when(plugin.isJournalReady()).thenThrow(new IllegalStateException("unavailable"));
        MarketTransactionLocks.initialize(journal);

        assertFalse(MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, 7));
    }

    @Test
    void reconciliationLookupFailureRejectsSafely() throws Exception {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        UUID playerId = UUID.randomUUID();
        AtomicInteger lookups = new AtomicInteger();
        when(plugin.isJournalReady()).thenReturn(true);
        when(journal.getUnresolvedRecords()).thenAnswer(invocation -> {
            if (lookups.incrementAndGet() == 1) {
                return List.of();
            }
            throw new IllegalStateException("unavailable");
        });
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));

        assertFalse(MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerId, 7));
        assertTrue(lookups.get() >= 2);
    }

    private static File temp() throws Exception {
        return Files.createTempDirectory("stonks-readiness").toFile();
    }
}
