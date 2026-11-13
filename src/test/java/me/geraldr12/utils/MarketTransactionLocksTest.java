package me.geraldr12.utils;

import me.geraldr12.Stonks;
import me.geraldr12.persistence.MarketTransactionJournal;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketTransactionLocksTest {

    @Test
    void lockRegistriesHaveBoundedSeparateSizes() throws Exception {
        for (int i = 0; i < 10_000; i++) {
            MarketTransactionLocks.company(i);
            MarketTransactionLocks.player(UUID.randomUUID());
        }

        Field companyLocks = MarketTransactionLocks.class.getDeclaredField("COMPANY_LOCKS");
        Field playerLocks = MarketTransactionLocks.class.getDeclaredField("PLAYER_LOCKS");
        companyLocks.setAccessible(true);
        playerLocks.setAccessible(true);
        assertEquals(256, ((Object[]) companyLocks.get(null)).length);
        assertEquals(256, ((Object[]) playerLocks.get(null)).length);
        assertTrue(MarketTransactionLocks.company(1) != MarketTransactionLocks.player(UUID.randomUUID()));
    }

    @Test
    void durableJournalIsRequiredBeforeReconciliationLookup() {
        assertFalse(MarketTransactionLocks.requiresReconciliation(UUID.randomUUID(), 41));
    }

    @Test
    void startupRejectsUnreadableJournalState() throws Exception {
        File root = Files.createTempDirectory("stonks-locks").toFile();
        File transactions = new File(root, "transactions");
        assertTrue(transactions.mkdirs());
        Files.writeString(new File(transactions, "not-a-uuid.yml").toPath(), "status: PREPARED");

        assertThrows(IllegalStateException.class,
                () -> MarketTransactionLocks.initialize(new MarketTransactionJournal(root)));
    }


    @Test
    void admissionBindsExactJournalAndTracksReleaseIdempotently() throws Exception {
        MarketTransactionJournal active = journal();
        MarketTransactionLocks.initialize(active);
        assertNull(MarketTransactionLocks.tryAdmit(active));
        Stonks pendingPlugin = org.mockito.Mockito.mock(Stonks.class);
        org.mockito.Mockito.when(pendingPlugin.isJournalReady()).thenReturn(true);
        UUID pendingPlayer = UUID.randomUUID();
        assertFalse(MarketTransactionLocks.isJournalLifecycleReady(pendingPlugin, active));
        assertFalse(MarketTransactionLocks.isFinanciallyReady(pendingPlugin, active, pendingPlayer));
        assertFalse(MarketTransactionLocks.isFinanciallyReady(pendingPlugin, active, pendingPlayer, 7));

        Stonks plugin = org.mockito.Mockito.mock(Stonks.class);
        org.mockito.Mockito.when(plugin.isJournalReady()).thenReturn(true);
        assertTrue(MarketTransactionLocks.activate(plugin, active));
        assertTrue(MarketTransactionLocks.isJournalLifecycleReady(plugin, active));

        MarketTransactionLocks.Admission admission = MarketTransactionLocks.tryAdmit(active);
        assertNotNull(admission);
        assertEquals(active, admission.journal());
        assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
        assertNull(MarketTransactionLocks.tryAdmit(journal()));

        admission.close();
        admission.close();
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

    @Test
    void generationsAreUniqueAndOldTokenCannotReleaseSuccessor() throws Exception {
        MarketTransactionJournal first = journal();
        MarketTransactionLocks.initialize(first);
        Stonks plugin = org.mockito.Mockito.mock(Stonks.class);
        org.mockito.Mockito.when(plugin.isJournalReady()).thenReturn(true);
        assertTrue(MarketTransactionLocks.activate(plugin, first));
        MarketTransactionLocks.Admission admission = MarketTransactionLocks.tryAdmit(first);
        assertNotNull(admission);
        long firstGeneration = admission.generation();

        assertThrows(IllegalStateException.class,
                () -> MarketTransactionLocks.initialize(journal()));
        admission.close();

        MarketTransactionJournal second = journal();
        MarketTransactionLocks.initialize(second);
        org.mockito.Mockito.when(plugin.isJournalReady()).thenReturn(true);
        assertTrue(MarketTransactionLocks.activate(plugin, second));
        MarketTransactionLocks.Admission successor = MarketTransactionLocks.tryAdmit(second);
        assertNotNull(successor);
        assertNotEquals(firstGeneration, successor.generation());
        assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
        successor.close();
    }

    @Test
    void revokeRejectsNewAdmissionsButExistingAdmissionCanRelease() throws Exception {
        MarketTransactionJournal active = journal();
        MarketTransactionLocks.initialize(active);
        Stonks plugin = org.mockito.Mockito.mock(Stonks.class);
        org.mockito.Mockito.when(plugin.isJournalReady()).thenReturn(true);
        assertTrue(MarketTransactionLocks.activate(plugin, active));
        MarketTransactionLocks.Admission admission = MarketTransactionLocks.tryAdmit(active);
        assertNotNull(admission);

        MarketTransactionLocks.revoke();
        assertNull(MarketTransactionLocks.tryAdmit(active));
        assertEquals(1, MarketTransactionLocks.activeAdmissionCount());

        admission.close();
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        assertNull(MarketTransactionLocks.tryAdmit(active));
    }

    @Test
    void admissionBeforeRevocationRemainsReleasableAndBlocksSuccessors() throws Exception {
        MarketTransactionJournal active = journal();
        MarketTransactionLocks.initialize(active);
        Stonks plugin = org.mockito.Mockito.mock(Stonks.class);
        org.mockito.Mockito.when(plugin.isJournalReady()).thenReturn(true);
        assertTrue(MarketTransactionLocks.activate(plugin, active));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch admissionEntered = new CountDownLatch(1);
        CountDownLatch allowAdmission = new CountDownLatch(1);
        CountDownLatch allowRevocation = new CountDownLatch(1);
        try {
            Future<MarketTransactionLocks.Admission> admitted = executor.submit(() -> {
                try {
                    admissionEntered.countDown();
                    allowAdmission.await();
                    return MarketTransactionLocks.tryAdmit(active);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            Future<?> revocation = executor.submit(() -> {
try {
                    admissionEntered.await();
                    allowRevocation.await();
                    MarketTransactionLocks.revoke();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });

            assertTrue(admissionEntered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            allowAdmission.countDown();
            MarketTransactionLocks.Admission token = admitted.get();
            assertNotNull(token);
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            allowRevocation.countDown();
            revocation.get();

            assertNull(MarketTransactionLocks.tryAdmit(active));
            token.close();
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void revocationBeforeAdmissionRejectsUnderControlledContention() throws Exception {
        MarketTransactionJournal active = journal();
        MarketTransactionLocks.initialize(active);
        Stonks plugin = org.mockito.Mockito.mock(Stonks.class);
        org.mockito.Mockito.when(plugin.isJournalReady()).thenReturn(true);
        assertTrue(MarketTransactionLocks.activate(plugin, active));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch admissionEntered = new CountDownLatch(1);
        CountDownLatch allowAdmission = new CountDownLatch(1);
        CountDownLatch allowRevocation = new CountDownLatch(1);
        try {
            Future<MarketTransactionLocks.Admission> rejected = executor.submit(() -> {
                try {
                    admissionEntered.countDown();
                    allowAdmission.await();
                    return MarketTransactionLocks.tryAdmit(active);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            Future<?> revocation = executor.submit(() -> {
try {
                    admissionEntered.await();
                    allowRevocation.await();
                    MarketTransactionLocks.revoke();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });

            assertTrue(admissionEntered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            allowRevocation.countDown();
            revocation.get();
            allowAdmission.countDown();

            assertNull(rejected.get());
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedInitializationPreservesActiveGeneration() throws Exception {
        MarketTransactionJournal active = journal();
        MarketTransactionLocks.initialize(active);
        long generation = MarketTransactionLocks.currentGeneration();
        Stonks plugin = org.mockito.Mockito.mock(Stonks.class);
        org.mockito.Mockito.when(plugin.isJournalReady()).thenReturn(true);
        assertTrue(MarketTransactionLocks.activate(plugin, active));
        File root = Files.createTempDirectory("stonks-invalid-lifecycle").toFile();
        File transactions = new File(root, "transactions");
        assertTrue(transactions.mkdirs());
        Files.writeString(new File(transactions, "not-a-uuid.yml").toPath(), "status: PREPARED");

        assertThrows(IllegalStateException.class,
                () -> MarketTransactionLocks.initialize(new MarketTransactionJournal(root)));
        assertEquals(generation, MarketTransactionLocks.currentGeneration());
        MarketTransactionLocks.Admission admission = MarketTransactionLocks.tryAdmit(active);
        assertNotNull(admission);
        admission.close();
    }

    @Test
    void lockIdentitiesRemainStableAcrossLifecyclePublication() throws Exception {
        Object companyLock = MarketTransactionLocks.company(41);
        UUID playerId = UUID.randomUUID();
        Object playerLock = MarketTransactionLocks.player(playerId);
        Object allocationLock = MarketTransactionLocks.companyAllocation();
        MarketTransactionLocks.initialize(journal());
        MarketTransactionLocks.initialize(journal());

        assertTrue(companyLock == MarketTransactionLocks.company(41));
        assertTrue(playerLock == MarketTransactionLocks.player(playerId));
        assertTrue(allocationLock == MarketTransactionLocks.companyAllocation());
    }

    private static MarketTransactionJournal journal() throws Exception {
        return new MarketTransactionJournal(Files.createTempDirectory("stonks-lifecycle").toFile());
    }
}
