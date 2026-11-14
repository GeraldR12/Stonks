package me.geraldr12.persistence;

import me.geraldr12.data.sources.yml.YmlDataSourceImpl;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JournalIntegrityValidationTest {
    @Test
    void diagnosticsAreBoundedAndSanitized() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-diagnostics").toFile();
        File transactions = new File(root, "transactions");
        assertTrue(transactions.mkdirs());
        for (int i = 0; i < 64; i++) {
            Files.writeString(new File(transactions, "bad-" + "x".repeat(80) + i).toPath(), "x");
        }
        JournalIntegrityResult result = new MarketTransactionJournal(root).inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.INVALID, result.getAssessment());
        assertTrue(result.getDiagnostics().size() <= 32);
        assertTrue(result.getDiagnostics().stream().allMatch(message -> message.length() <= 160));
        assertTrue(result.getDiagnostics().stream().noneMatch(message -> message.contains("bad-")));
        assertTrue(result.getDiagnostics().stream().anyMatch(message -> message.equals(
                "additional integrity diagnostics suppressed")));
        assertThrows(UnsupportedOperationException.class, () -> result.getDiagnostics().clear());
    }

    @Test
    void pendingFileSymlinkWithValidUuidIsRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-pending-link").toFile();
        File outsideRoot = Files.createTempDirectory("stonks-integrity-pending-target").toFile();
        File transactions = new File(root, "transactions");
        assertTrue(transactions.mkdirs());
        MarketTransactionRecord record = record(new MarketTransactionJournal(outsideRoot), false);
        Path outsideFile = new File(outsideRoot, "target.yml").toPath();
        assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(outsideRoot)
                .save("target.yml", record));
        Path link = new File(transactions, record.getTransactionId() + ".yml").toPath();
        createSymbolicLinkOrSkip(link, outsideFile);
        JournalIntegrityResult result = new MarketTransactionJournal(root).inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.INVALID, result.getAssessment());
        assertTrue(Files.exists(outsideFile));
    }

    @Test
    void absentDirectoriesRequireBootstrapWithoutMutation() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-empty").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        JournalIntegrityResult result = journal.inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.BOOTSTRAP_REQUIRED, result.getAssessment());
        assertFalse(new File(root, "transactions").exists());
        assertTrue(journal.bootstrapDirectories());
        assertEquals(JournalIntegrityAssessment.VALID, journal.inspectIntegrity().getAssessment());
    }

    @Test
    void populatedPendingRecordIsValidated() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-pending").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, false);
        JournalIntegrityResult result = journal.inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.VALID, result.getAssessment());
        assertEquals(1, result.getPendingRecordCount());
        assertEquals(record.getStateRevision(), new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow().getStateRevision());
    }

    @Test
    void archivedOnlyTerminalRecordIsAccepted() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-archive").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, true);
        record.setSchemaVersion(MarketTransactionRecord.LEGACY_SCHEMA_VERSION);
        record.setStatus(MarketTransactionStatus.MARKET_IMPACT_APPLIED);
        assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(root)
                .save("transactions/" + record.getTransactionId() + ".yml", record));
        assertTrue(journal.complete(record));
        JournalIntegrityResult result = journal.inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.VALID, result.getAssessment());
        assertEquals(0, result.getPendingRecordCount());
        assertEquals(1, result.getArchivedRecordCount());
    }

    @Test
    void conflictingPendingAndArchiveRecordsAreRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-conflict").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, false);
        MarketTransactionRecord conflict = recordCopy(record);
        conflict.setStateRevision(record.getStateRevision() + 1);
        YmlDataSourceImpl<MarketTransactionRecord> source = new YmlDataSourceImpl<>(root);
        assertTrue(source.save("transactions/archive/" + record.getTransactionId() + ".yml", conflict));
        JournalIntegrityResult result = journal.inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.INVALID, result.getAssessment());
    }

    @Test
    void invalidRevisionAndMalformedYamlFailClosed() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-invalid").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, false);
        record.setStateRevision(-1);
        YmlDataSourceImpl<MarketTransactionRecord> source = new YmlDataSourceImpl<>(root);
        assertTrue(source.save("transactions/" + record.getTransactionId() + ".yml", record));
        assertEquals(JournalIntegrityAssessment.INVALID, journal.inspectIntegrity().getAssessment());
        assertTrue(Files.writeString(new File(root, "transactions/corrupt.yml").toPath(), "not: [yaml" ).toFile().exists());
        assertEquals(JournalIntegrityAssessment.INVALID, journal.inspectIntegrity().getAssessment());
    }

    @Test
    void nonterminalArchiveIsRejectedAndUnsupportedEntriesAreReported() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-unsupported").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, false);
        YmlDataSourceImpl<MarketTransactionRecord> source = new YmlDataSourceImpl<>(root);
        assertTrue(source.save("transactions/archive/" + record.getTransactionId() + ".yml", record));
        assertTrue(Files.writeString(new File(root, "transactions/archive/notes.txt").toPath(), "x").toFile().exists());
        JournalIntegrityResult result = journal.inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.INVALID, result.getAssessment());
        assertTrue(result.getDiagnostics().stream().anyMatch(message -> message.contains("nonterminal")));
    }

    @Test
    void g0ManualRecoveryRemainsDisabledAndInspectionDoesNotMutate() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-readonly").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, false);
        long revision = record.getStateRevision();
        assertFalse(journal.resolveAfterManualRepair(record, "ignored"));
        assertEquals(revision, record.getStateRevision());
        journal.inspectIntegrity();
        assertEquals(revision, new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow().getStateRevision());
    }

@Test
    void archiveFileSymlinkWithValidUuidIsRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-archive-link").toFile();
        File outsideRoot = Files.createTempDirectory("stonks-integrity-archive-target").toFile();
        File transactions = new File(root, "transactions");
        File archive = new File(transactions, "archive");
        assertTrue(transactions.mkdirs());
        assertTrue(archive.mkdirs());
        MarketTransactionRecord record = record(new MarketTransactionJournal(outsideRoot), false);
        Path outsideFile = new File(outsideRoot, "target.yml").toPath();
        assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(outsideRoot)
                .save("target.yml", record));
        Path link = new File(archive, record.getTransactionId() + ".yml").toPath();
        createSymbolicLinkOrSkip(link, outsideFile);
        JournalIntegrityResult result = new MarketTransactionJournal(root).inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.INVALID, result.getAssessment());
        assertTrue(Files.exists(outsideFile));
    }

    @Test
    void pendingDirectorySymlinkIsRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-pending-directory-link").toFile();
        File outside = Files.createTempDirectory("stonks-integrity-pending-directory-target").toFile();
        createSymbolicLinkOrSkip(new File(root, "transactions").toPath(), outside.toPath());
        JournalIntegrityResult result = new MarketTransactionJournal(root).inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.INVALID, result.getAssessment());
    }

    @Test
    void archiveDirectorySymlinkIsRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-archive-directory-link").toFile();
        File transactions = new File(root, "transactions");
        File outside = Files.createTempDirectory("stonks-integrity-archive-directory-target").toFile();
        assertTrue(transactions.mkdirs());
        createSymbolicLinkOrSkip(new File(transactions, "archive").toPath(), outside.toPath());
        JournalIntegrityResult result = new MarketTransactionJournal(root).inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.INVALID, result.getAssessment());
    }

    @Test
    void nestedDirectoryIsRejectedWhileDirectRecordRemainsValid() throws Exception {
        File root = Files.createTempDirectory("stonks-integrity-nested-directory").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, false);
        assertTrue(new File(root, "transactions/nested").mkdirs());
        JournalIntegrityResult result = journal.inspectIntegrity();
        assertEquals(JournalIntegrityAssessment.INVALID, result.getAssessment());
        assertEquals(1, result.getPendingRecordCount());
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getClass().getSimpleName());
        }
    }

    private MarketTransactionRecord record(MarketTransactionJournal journal, boolean terminal) {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(UUID.randomUUID().toString());
        record.setOperation("SELL");
        record.setPlayerUuid(UUID.randomUUID().toString());
        record.setCompanyId(7L);
        record.setQuantity(2);
        record.setValue(10.0);
        record.setQuoteCompanyId(7L);
        record.setQuotePlayerUuid(record.getPlayerUuid());
        record.setQuoteQuantity(2);
        record.setQuoteUnitSharePrice(5.0);
        record.setQuoteTotalValue(10.0);
        record.setQuoteTimestamp(System.currentTimeMillis());
        record.setTimestamp(record.getQuoteTimestamp());
        if (journal.prepareCurrentSchema(record) == null) throw new AssertionError("prepare failed");
        if (terminal) {
            record.setStatus(MarketTransactionStatus.COMPLETED);
        }
        return record;
    }

    private MarketTransactionRecord recordCopy(MarketTransactionRecord source) {
        MarketTransactionRecord copy = new MarketTransactionRecord();
        copy.setSchemaVersion(source.getSchemaVersion());
        copy.setStateRevision(source.getStateRevision());
        copy.setTransactionId(source.getTransactionId());
        copy.setOperation(source.getOperation());
        copy.setPlayerUuid(source.getPlayerUuid());
        copy.setCompanyId(source.getCompanyId());
        copy.setQuantity(source.getQuantity());
        copy.setValue(source.getValue());
        copy.setQuoteCompanyId(source.getQuoteCompanyId());
        copy.setQuotePlayerUuid(source.getQuotePlayerUuid());
        copy.setQuoteQuantity(source.getQuoteQuantity());
        copy.setQuoteUnitSharePrice(source.getQuoteUnitSharePrice());
        copy.setQuoteTotalValue(source.getQuoteTotalValue());
        copy.setQuoteTimestamp(source.getQuoteTimestamp());
        copy.setTimestamp(source.getTimestamp());
        copy.setStatus(source.getStatus());
        copy.setFailureReason(source.getFailureReason());
        copy.setLegacyMigrated(source.isLegacyMigrated());
        copy.setEffects(source.getEffects());
        return copy;
    }
}
