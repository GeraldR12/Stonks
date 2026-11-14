package me.geraldr12.persistence;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketTransactionRevisionTest {
    @Test
    void newRecordStartsAtOneAndReloadPreservesRevision() throws Exception {
        File root = Files.createTempDirectory("stonks-revision-new").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = baseRecord();

        assertEquals(0, record.getStateRevision());
        assertNotNull(journal.prepareCurrentSchema(record));
        assertEquals(1, record.getStateRevision());
        assertEquals(1, new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow().getStateRevision());
    }

    @Test
    void successfulLogicalChangesIncrementExactlyOnceAndNoOpsDoNot() throws Exception {
        File root = Files.createTempDirectory("stonks-revision-paths").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = baseRecord();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertEquals(1, record.getStateRevision());

        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                10, 0, 2));
        assertEquals(2, record.getStateRevision());
        assertTrue(!journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                10, 0, 2));
        assertEquals(2, record.getStateRevision());

        String effectId = record.getEffects().get(0).getEffectId();
        assertTrue(journal.recordEffectOutcome(record, effectId,
                MarketTransactionEffectState.DEFINITE_REJECTED, "rejected"));
        assertEquals(3, record.getStateRevision());
        assertTrue(!journal.recordEffectOutcome(record, effectId,
                MarketTransactionEffectState.DEFINITE_REJECTED, "again"));
        assertEquals(3, record.getStateRevision());
    }

    @Test
    void appendEvidenceObservationAndProposalEachPreserveMonotonicRevision() throws Exception {
        File root = Files.createTempDirectory("stonks-revision-append").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        long revision = record.getStateRevision();

        ReconciliationEvidence evidence = ReconciliationEvidence.create(record.getTransactionId(),
                record.getEffects().get(0).getEffectId(), ReconciliationEvidenceType.OPERATOR_ATTESTATION,
                "ref", record.getPlayerUuid(), "operator", "statement", 2000, "revision-evidence");
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED, journal.appendEvidence(evidence));
        record = reloaded(journal, record);
        assertEquals(++revision, record.getStateRevision());

        VerificationObservation observation = VerificationObservation.create(record.getTransactionId(),
                record.getEffects().get(0).getEffectId(), record.getEffects().get(0).getType(),
                VerificationObservationSource.UNAVAILABLE, VerificationObservationAssessment.NOT_VERIFIABLE,
                "ref", "operator-supplied", 1000,
                "revision-observation", "unconfirmed");
        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.RECORDED,
                journal.appendVerificationObservation(observation));
        record = reloaded(journal, record);
        assertEquals(++revision, record.getStateRevision());

        RecoveryProposal proposal = RecoveryProposal.create(record.getTransactionId(), "revision-proposal",
                java.util.List.of(record.getEffects().get(0).getEffectId()),
                java.util.List.of(record.getEffects().get(0).getType()), RecoveryProposalDisposition.NOT_VERIFIABLE,
                RecoveryProposalStatus.AWAITING_AUTHORITATIVE_PROOF, journal.financialStateFingerprint(record),
                java.util.List.of(observation.getObservationId()), java.util.List.of(evidence.getEvidenceId()),
                record.getEffects().get(0).getAmount(), record.getEffects().get(0).getQuantity(),
                "operator-supplied", System.currentTimeMillis(), System.currentTimeMillis() + 60_000,
                "not independently verifiable");
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.RECORDED,
                journal.appendRecoveryProposal(proposal));
        record = reloaded(journal, record);
        assertEquals(++revision, record.getStateRevision());
    }

    @Test
    void failedPersistenceDoesNotAdvanceRevision() throws Exception {
        File root = Files.createTempDirectory("stonks-revision-failure").toFile();
        MarketTransactionJournal setup = new MarketTransactionJournal(root);
        MarketTransactionRecord record = baseRecord();
        assertNotNull(setup.prepareCurrentSchema(record));
        long revision = record.getStateRevision();

        MarketTransactionJournal failing = new MarketTransactionJournal(root, ignored -> false);
        assertTrue(!failing.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                10, 0, 2));
        assertEquals(revision, new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow().getStateRevision());
    }

    @Test
    void savePolicyExceptionRestoresCallerRevision() throws Exception {
        File root = Files.createTempDirectory("stonks-revision-exception").toFile();
        MarketTransactionJournal setup = new MarketTransactionJournal(root);
        MarketTransactionRecord record = baseRecord();
        assertNotNull(setup.prepareCurrentSchema(record));
        long revision = record.getStateRevision();

        MarketTransactionJournal throwing = new MarketTransactionJournal(root, ignored -> {
            throw new IllegalStateException("test save policy failure");
        });
        try {
            throwing.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                    10, 0, 2);
        } catch (IllegalStateException expected) {
            assertEquals(revision, record.getStateRevision());
            return;
        }
        throw new AssertionError("expected save policy exception");
    }

    @Test
    void negativeRevisionFailsClosed() throws Exception {
        File root = Files.createTempDirectory("stonks-revision-negative").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = baseRecord();
        record.setStateRevision(-1);
        assertTrue(journal.prepareCurrentSchema(record) == null);
    }

    @Test
    void terminalArchivePreservesRevisionAndRecoveryDoesNotIncrement() throws Exception {
        File root = Files.createTempDirectory("stonks-revision-archive").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = baseRecord();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                10, 0, 2));
        String effectId = record.getEffects().get(0).getEffectId();
        assertTrue(journal.recordEffectOutcome(record, effectId,
                MarketTransactionEffectState.DEFINITE_REJECTED, "rejected"));
        assertTrue(journal.abort(record, "terminal test"));
        long archivedRevision = record.getStateRevision();
        assertTrue(archivedRevision > 0);

        assertTrue(journal.recoverTerminalPendingRecords());
        assertEquals(archivedRevision, record.getStateRevision());
        assertTrue(!new MarketTransactionJournal(root).getRecord(record.getTransactionId()).isPresent());
    }

    @Test
    void missingLegacyRevisionUsesZeroBaselineAndNextMutationIncrements() throws Exception {
        File root = Files.createTempDirectory("stonks-revision-legacy").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = baseRecord();
        record.setSchemaVersion(MarketTransactionRecord.LEGACY_SCHEMA_VERSION);
        assertNotNull(journal.prepare(record));
        MarketTransactionRecord loaded = new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow();
        assertEquals(1, loaded.getStateRevision());
    }

    private MarketTransactionRecord reloaded(MarketTransactionJournal journal,
                                             MarketTransactionRecord record) {
        return journal.getRecord(record.getTransactionId()).orElseThrow();
    }

    private MarketTransactionRecord blockingRecord(MarketTransactionJournal journal) {
        MarketTransactionRecord record = baseRecord();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                10, 0, 2));
        assertTrue(journal.markReconciliationRequired(record, "revision test"));
        return record;
    }

    private MarketTransactionRecord baseRecord() {
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
        return record;
    }
}
