package me.geraldr12.utils;

import me.geraldr12.Stonks;
import me.geraldr12.persistence.MarketTransactionEffectState;
import me.geraldr12.persistence.MarketTransactionEffectType;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.persistence.MarketTransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationScopeCharacterizationTest {
    private static final long COMPANY_E = 41L;
    private static final long COMPANY_F = 42L;

    @TempDir
    Path tempDir;

    @Test
    void pendingRecordBlocksItsPlayerEverywhereAndOtherPlayersOnlyInItsCompany() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        UUID playerX = UUID.randomUUID();
        UUID playerY = UUID.randomUUID();
        prepare(journal, playerX, COMPANY_E);

        assertScope(journal, playerX, playerY, true);
    }

    @Test
    void restartAndAcknowledgementDoNotReleaseTheScope() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        UUID playerX = UUID.randomUUID();
        UUID playerY = UUID.randomUUID();
        MarketTransactionRecord record = prepare(journal, playerX, COMPANY_E);
        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());
        assertTrue(reopened.recoverTerminalPendingRecords());
        MarketTransactionRecord recovered = reopened.getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, recovered.getStatus());
        assertTrue(reopened.acknowledge(recovered, "Staff review recorded without repair"));

        assertScope(new MarketTransactionJournal(tempDir.toFile()), playerX, playerY, true);
    }

    @Test
    void archivedTerminalRecordsDoNotBlockEitherPlayerOrCompany() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        UUID playerX = UUID.randomUUID();
        UUID playerY = UUID.randomUUID();
        MarketTransactionRecord aborted = prepare(journal, playerX, COMPANY_E);
        effect(journal, aborted, MarketTransactionEffectType.ECONOMY_DEBIT, 10,
                MarketTransactionEffectState.DEFINITE_REJECTED);
        assertTrue(journal.abort(aborted, "Withdrawal rejected"));

        MarketTransactionRecord completed = prepare(journal, playerX, COMPANY_E);
        effect(journal, completed, MarketTransactionEffectType.ECONOMY_DEBIT, 10,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, completed, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 20,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, completed, MarketTransactionEffectType.PLAYER_SHARE_ADD, 30,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, completed, MarketTransactionEffectType.MARKET_IMPACT, 70,
                MarketTransactionEffectState.CONFIRMED);
        assertTrue(journal.complete(completed));

        MarketTransactionRecord compensated = prepare(journal, playerX, COMPANY_E);
        effect(journal, compensated, MarketTransactionEffectType.ECONOMY_DEBIT, 10,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, compensated, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 20,
                MarketTransactionEffectState.DEFINITE_REJECTED);
        effect(journal, compensated, MarketTransactionEffectType.ECONOMY_REFUND, 60,
                MarketTransactionEffectState.CONFIRMED);
        assertTrue(journal.saveCompensated(compensated));
        assertFalse(journal.getAllPendingOrReconciliationRecords().isEmpty());
        assertScope(journal, playerX, playerY, false);
        assertTrue(journal.recoverTerminalPendingRecords());

        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());
        assertEquals(MarketTransactionStatus.ABORTED, aborted.getStatus());
        assertEquals(MarketTransactionStatus.COMPLETED, completed.getStatus());
        assertEquals(MarketTransactionStatus.COMPENSATED, compensated.getStatus());
        for (MarketTransactionRecord record : new MarketTransactionRecord[]{aborted, completed, compensated}) {
            assertTrue(tempDir.resolve("transactions/archive/" + record.getTransactionId() + ".yml").toFile().isFile());
        }
        assertTrue(reopened.getAllPendingOrReconciliationRecords().isEmpty());
        assertScope(reopened, playerX, playerY, false);
    }

    @Test
    void measuresReconciliationLookupUnderAllocationLock() {
        UUID unrelatedPlayer = UUID.randomUUID();
        for (int count : new int[]{0, 10, 100}) {
            MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.resolve("pending-" + count).toFile());
            assertTrue(journal.bootstrapDirectories());
            for (int i = 0; i < count; i++) {
                prepare(journal, UUID.randomUUID(), COMPANY_E);
            }
            assertEquals(count, journal.getUnresolvedRecords().size());
            MarketTransactionLocks.initialize(journal);
            try {
                synchronized (MarketTransactionLocks.companyAllocation()) {
                    for (int i = 0; i < 20; i++) {
                        assertFalse(MarketTransactionLocks.requiresReconciliation(unrelatedPlayer, COMPANY_F));
                    }
                    double[] samples = new double[5];
                    boolean blocked = false;
                    for (int batch = 0; batch < samples.length; batch++) {
                        long start = System.nanoTime();
                        for (int i = 0; i < 100; i++) {
                            blocked |= MarketTransactionLocks.requiresReconciliation(unrelatedPlayer, COMPANY_F);
                        }
                        samples[batch] = (System.nanoTime() - start) / 100.0 / 1_000_000.0;
                    }
                    assertFalse(blocked);
                    Arrays.sort(samples);
                    System.out.printf(Locale.ROOT,
                            "RECONCILIATION_BENCH pending=%d warmup=20 batches=5 calls_per_batch=100 ms_per_call_min=%.6f median=%.6f max=%.6f%n",
                            count, samples[0], samples[2], samples[4]);
                }
            } finally {
                MarketTransactionLocks.revoke();
            }
        }
    }

    private void assertScope(MarketTransactionJournal journal, UUID playerX, UUID playerY, boolean pending) {
        Stonks plugin = mock(Stonks.class);
        when(plugin.isJournalReady()).thenReturn(true);
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
        try {
            assertEquals(pending, MarketTransactionLocks.requiresReconciliation(playerX, COMPANY_E));
            assertEquals(pending, MarketTransactionLocks.requiresReconciliation(playerX, COMPANY_F));
            assertEquals(pending, MarketTransactionLocks.requiresReconciliation(playerY, COMPANY_E));
            assertFalse(MarketTransactionLocks.requiresReconciliation(playerY, COMPANY_F));
            assertEquals(!pending, MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerX));
            assertTrue(MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerY));
            assertEquals(!pending, MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerX, COMPANY_E));
            assertEquals(!pending, MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerX, COMPANY_F));
            assertEquals(!pending, MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerY, COMPANY_E));
            assertTrue(MarketTransactionLocks.isFinanciallyReady(plugin, journal, playerY, COMPANY_F));
        } finally {
            MarketTransactionLocks.revoke();
        }
    }

    private MarketTransactionRecord prepare(MarketTransactionJournal journal, UUID player, long company) {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(UUID.randomUUID().toString());
        record.setOperation("BUY");
        record.setPlayerUuid(player.toString());
        record.setCompanyId(company);
        record.setQuantity(2);
        record.setValue(20.0);
        record.setQuoteCompanyId(company);
        record.setQuotePlayerUuid(player.toString());
        record.setQuoteQuantity(2);
        record.setQuoteUnitSharePrice(10.0);
        record.setQuoteTotalValue(20.0);
        record.setQuoteTimestamp(System.currentTimeMillis());
        record.setTimestamp(record.getQuoteTimestamp());
        assertNotNull(journal.prepareCurrentSchema(record));
        return record;
    }

    private void effect(MarketTransactionJournal journal, MarketTransactionRecord record,
                        MarketTransactionEffectType type, int sequence, MarketTransactionEffectState state) {
        double amount = type == MarketTransactionEffectType.ECONOMY_DEBIT
                || type == MarketTransactionEffectType.ECONOMY_REFUND ? record.getValue() : 0.0;
        assertTrue(journal.requestEffect(record, type, sequence, amount, record.getQuantity()));
        String effectId = record.getEffects().stream().filter(effect -> effect.getType() == type)
                .findFirst().orElseThrow().getEffectId();
        assertTrue(journal.recordEffectOutcome(record, effectId, state, "Characterization outcome"));
    }
}
