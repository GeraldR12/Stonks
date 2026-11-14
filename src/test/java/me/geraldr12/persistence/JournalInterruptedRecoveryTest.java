package me.geraldr12.persistence;

import me.geraldr12.data.sources.yml.YmlDataSourceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalInterruptedRecoveryTest {

    @TempDir
    Path directory;

    @Test
    void interruptedDeleteCanBeAcknowledgedAfterStartupRecovery() {
        MarketTransactionJournal journal = new MarketTransactionJournal(directory.toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepare(record));
        assertTrue(journal.markEconomyApplied(record));
        assertFalse(journal.acknowledge(record, "operator inspected interrupted delete"));

        MarketTransactionJournal restarted = new MarketTransactionJournal(directory.toFile());
        assertTrue(restarted.recoverTerminalPendingRecords());

        MarketTransactionRecord recovered = restarted.getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, recovered.getStatus());
        assertEquals("Interrupted at status ECONOMY_APPLIED; found pending at startup",
                recovered.getFailureReason());
        assertTrue(restarted.acknowledge(recovered, "operator inspected interrupted delete"));
        MarketTransactionRecord acknowledged = new MarketTransactionJournal(directory.toFile())
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, acknowledged.getStatus());
        assertTrue(restarted.isBlocking(acknowledged));
        assertEquals("Acknowledged without automatic repair: operator inspected interrupted delete",
                acknowledged.getFailureReason());
        assertFalse(restarted.resolveAfterManualRepair(acknowledged, "manual-repair-confirmed: receipt"));
    }

    @Test
    void preparedCurrentSchemaRecordRequiresReconciliation() {
        assertInterruptedCurrentSchemaRecord(MarketTransactionStatus.PREPARED);
    }

    @Test
    void inProgressCurrentSchemaRecordRequiresReconciliation() {
        assertInterruptedCurrentSchemaRecord(MarketTransactionStatus.IN_PROGRESS);
    }

    private void assertInterruptedCurrentSchemaRecord(MarketTransactionStatus status) {
        MarketTransactionJournal journal = new MarketTransactionJournal(directory.toFile());
        MarketTransactionRecord record = record();
        record.setOperation("BUY");
        record.setQuantity(2);
        record.setQuoteCompanyId(record.getCompanyId());
        record.setQuotePlayerUuid(record.getPlayerUuid());
        record.setQuoteQuantity(2);
        record.setQuoteUnitSharePrice(5.0);
        record.setQuoteTotalValue(record.getValue());
        record.setQuoteTimestamp(record.getTimestamp());
        assertNotNull(journal.prepareCurrentSchema(record));
        if (status == MarketTransactionStatus.IN_PROGRESS) {
            assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                    10, record.getValue(), record.getQuantity()));
        }
        assertEquals(status, record.getStatus());

        MarketTransactionJournal restarted = new MarketTransactionJournal(directory.toFile());
        assertTrue(restarted.recoverTerminalPendingRecords());

        MarketTransactionRecord recovered = restarted.getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, recovered.getStatus());
        assertEquals("Interrupted at status " + status + "; found pending at startup",
                recovered.getFailureReason());
        assertEquals(record.getEffects(), recovered.getEffects());
        assertTrue(restarted.isBlocking(recovered));
    }

    @Test
    void existingReconciliationReasonIsPreservedAcrossRepeatedRecovery() {
        MarketTransactionJournal journal = new MarketTransactionJournal(directory.toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepare(record));
        assertTrue(journal.markReconciliationRequired(record, "provider outcome unknown"));

        MarketTransactionJournal restarted = new MarketTransactionJournal(directory.toFile());
        assertTrue(restarted.recoverTerminalPendingRecords());
        assertTrue(restarted.recoverTerminalPendingRecords());

        assertEquals(record, restarted.getRecord(record.getTransactionId()).orElseThrow());
    }

    @Test
    void completedPendingRecordIsArchivedWithoutEscalation() {
        MarketTransactionRecord record = record();
        record.setStatus(MarketTransactionStatus.COMPLETED);
        YmlDataSourceImpl<MarketTransactionRecord> source = new YmlDataSourceImpl<>(directory.toFile());
        String filename = record.getTransactionId() + ".yml";
        assertTrue(source.save("transactions/" + filename, record));

        MarketTransactionJournal restarted = new MarketTransactionJournal(directory.toFile());
        assertTrue(restarted.recoverTerminalPendingRecords());

        assertFalse(new File(directory.toFile(), "transactions/" + filename).exists());
        assertTrue(restarted.getRecord(record.getTransactionId()).isEmpty());
        assertEquals(record, source.load("transactions/archive/" + filename, MarketTransactionRecord.class));
        assertTrue(restarted.recoverTerminalPendingRecords());
    }

    @Test
    void escalationSaveFailureIsReportedAndCanBeRetried() {
        MarketTransactionJournal journal = new MarketTransactionJournal(directory.toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepare(record));
        assertTrue(journal.markEconomyApplied(record));

        MarketTransactionJournal failing = new MarketTransactionJournal(directory.toFile(), ignored -> false);
        assertFalse(failing.recoverTerminalPendingRecords());
        assertEquals(record, journal.getRecord(record.getTransactionId()).orElseThrow());

        assertTrue(journal.recoverTerminalPendingRecords());
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED,
                journal.getRecord(record.getTransactionId()).orElseThrow().getStatus());
    }

    private MarketTransactionRecord record() {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(UUID.randomUUID().toString());
        record.setOperation("DELETE");
        record.setPlayerUuid(UUID.randomUUID().toString());
        record.setCompanyId(7L);
        record.setValue(10.0);
        record.setTimestamp(System.currentTimeMillis());
        return record;
    }
}
