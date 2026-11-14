package me.geraldr12.persistence;

import me.geraldr12.data.sources.yml.YmlDataSourceImpl;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.IntPredicate;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryProposalTest {
    @Test
    void appendReloadsAndIdenticalRequestIsIdempotent() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-proposal").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        RecoveryProposal first = proposal(journal, record, "same-key");
        RecoveryProposal second = new RecoveryProposal(UUID.randomUUID().toString(), first.getTransactionId(),
                first.getIdempotencyKey(), first.getTargetEffectIds(), first.getTargetEffectTypes(), first.getDisposition(),
                first.getStatus(), first.getTransactionFingerprint(), first.getObservationIds(), first.getEvidenceIds(),
                first.getExpectedAmount(), first.getExpectedQuantity(), first.getProvenance(), first.getProposedAt(),
                first.getRecordedAt() + 1000, first.getExpiresAt(), first.getDiagnostic());

        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.RECORDED,
                journal.appendRecoveryProposal(first));
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.ALREADY_RECORDED,
                new MarketTransactionJournal(root).appendRecoveryProposal(second));
        assertEquals(1, new MarketTransactionJournal(root).getRecord(record.getTransactionId()).orElseThrow()
                .getRecoveryProposalsView().size());
    }

    @Test
    void concurrentIdenticalRequestsPersistOneProposal() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-concurrent").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        RecoveryProposal first = proposal(journal, record, "concurrent-key");
        RecoveryProposal second = new RecoveryProposal(UUID.randomUUID().toString(), first.getTransactionId(),
                first.getIdempotencyKey(), first.getTargetEffectIds(), first.getTargetEffectTypes(), first.getDisposition(),
                first.getStatus(), first.getTransactionFingerprint(), first.getObservationIds(), first.getEvidenceIds(),
                first.getExpectedAmount(), first.getExpectedQuantity(), first.getProvenance(), first.getProposedAt(),
                first.getRecordedAt() + 1, first.getExpiresAt(), first.getDiagnostic());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MarketTransactionJournal.RecoveryProposalAppendResult> left = executor.submit(
                    () -> new MarketTransactionJournal(root).appendRecoveryProposal(first));
            Future<MarketTransactionJournal.RecoveryProposalAppendResult> right = executor.submit(
                    () -> new MarketTransactionJournal(root).appendRecoveryProposal(second));
            assertTrue((left.get() == MarketTransactionJournal.RecoveryProposalAppendResult.RECORDED
                    && right.get() == MarketTransactionJournal.RecoveryProposalAppendResult.ALREADY_RECORDED)
                    || (right.get() == MarketTransactionJournal.RecoveryProposalAppendResult.RECORDED
                    && left.get() == MarketTransactionJournal.RecoveryProposalAppendResult.ALREADY_RECORDED));
        } finally {
            executor.shutdownNow();
        }
        MarketTransactionRecord reloaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(1, reloaded.getRecoveryProposalsView().size());
        assertEquals(1, reloaded.getRecoveryProposalsView().stream()
                .map(RecoveryProposal::getIdempotencyKey).distinct().count());
        assertEquals(10.0, reloaded.getValue());
    }

    @Test
    void addedEvidenceOrObservationNeedsReevaluation() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-freshness").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        RecoveryProposal empty = proposal(journal, record, "freshness-empty");
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.RECORDED,
                journal.appendRecoveryProposal(empty));
        VerificationObservation observation = VerificationObservation.create(record.getTransactionId(),
                record.getEffects().get(0).getEffectId(), record.getEffects().get(0).getType(),
                VerificationObservationSource.UNAVAILABLE, VerificationObservationAssessment.NOT_VERIFIABLE,
                "ref", "operator-supplied", 1000, "observation-key", "unconfirmed");
        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.RECORDED,
                journal.appendVerificationObservation(observation));
        RecoveryProposal stored = new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow().getRecoveryProposalsView().get(0);
        assertEquals(MarketTransactionJournal.RecoveryProposalFreshness.NEEDS_REEVALUATION,
                journal.assessRecoveryProposalFreshness(stored));

        File evidenceRoot = Files.createTempDirectory("stonks-recovery-evidence-freshness").toFile();
        MarketTransactionJournal evidenceJournal = new MarketTransactionJournal(evidenceRoot);
        MarketTransactionRecord evidenceRecord = blockingRecord(evidenceJournal);
        ReconciliationEvidence evidence = ReconciliationEvidence.create(evidenceRecord.getTransactionId(),
                evidenceRecord.getEffects().get(0).getEffectId(), ReconciliationEvidenceType.OPERATOR_ATTESTATION,
                "ref", evidenceRecord.getPlayerUuid(), "operator", "statement", 2000, "evidence-key");
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED,
                evidenceJournal.appendEvidence(evidence));
        RecoveryProposal evidenceProposal = proposal(evidenceJournal,
                evidenceJournal.getRecord(evidenceRecord.getTransactionId()).orElseThrow(), "freshness-evidence");
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.RECORDED,
                evidenceJournal.appendRecoveryProposal(evidenceProposal));
        ReconciliationEvidence additional = ReconciliationEvidence.create(evidenceRecord.getTransactionId(),
                evidenceRecord.getEffects().get(0).getEffectId(), ReconciliationEvidenceType.OPERATOR_ATTESTATION,
                "ref-2", evidenceRecord.getPlayerUuid(), "operator", "second statement", 3000, "evidence-key-2");
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED,
                evidenceJournal.appendEvidence(additional));
        RecoveryProposal storedEvidenceProposal = new MarketTransactionJournal(evidenceRoot)
                .getRecord(evidenceRecord.getTransactionId()).orElseThrow().getRecoveryProposalsView().get(0);
        assertEquals(MarketTransactionJournal.RecoveryProposalFreshness.NEEDS_REEVALUATION,
                evidenceJournal.assessRecoveryProposalFreshness(storedEvidenceProposal));
    }

    @Test
    void genuinelyPersistedExpiredProposalRemainsReadableAndNonExecutable() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-expiry").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        long proposedAt = System.currentTimeMillis() - 2_000;
        long expiresAt = System.currentTimeMillis() + 60_000;
        RecoveryProposal valid = proposalAt(journal, record, "expiring-key", proposedAt, expiresAt);
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.RECORDED,
                journal.appendRecoveryProposal(valid));
        RecoveryProposal persisted = new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow().getRecoveryProposalsView().get(0);
        assertEquals(valid.getProposalId(), persisted.getProposalId());
        assertEquals(MarketTransactionJournal.RecoveryProposalFreshness.EXPIRED,
                journal.assessRecoveryProposalFreshness(persisted, expiresAt + 1));
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.ALREADY_RECORDED,
                journal.appendRecoveryProposal(persisted));
        assertTrue(persisted.getStatus() != RecoveryProposalStatus.RECORDED
                || persisted.getDisposition() != RecoveryProposalDisposition.PROPOSE_RECOVERY);
    }

    @Test
    void persistenceRejectionRollsBackAndDoesNotRetry() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-persistence").toFile();
        MarketTransactionJournal setup = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(setup);
        RecoveryProposal proposal = proposal(setup, record, "persistence-key");
        MarketTransactionJournal failing = new MarketTransactionJournal(root, attempt -> false);
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.PERSISTENCE_FAILED,
                failing.appendRecoveryProposal(proposal));
        MarketTransactionRecord reloaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertTrue(reloaded.getRecoveryProposalsView().isEmpty());
        assertTrue(reloaded.getStatus().isBlocking());
        assertEquals(1, reloaded.getEffects().size());
    }

    @Test
    void schemaCompatibilityAndCorruptProposalCollectionsFailClosed() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-corruption").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        record.setSchemaVersion(MarketTransactionRecord.LEGACY_SCHEMA_VERSION);
        record.replaceRecoveryProposals(null);
        assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(root)
                .save("transactions/" + record.getTransactionId() + ".yml", record));
        assertTrue(new MarketTransactionJournal(root).getRecord(record.getTransactionId()).isPresent());

        RecoveryProposal valid = proposal(journal, record, "corrupt-key");
        List<RecoveryProposal> corrupt = new ArrayList<>();
        corrupt.add(valid);
        corrupt.add(null);
        record.replaceRecoveryProposals(corrupt);
        assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(root)
                .save("transactions/" + record.getTransactionId() + ".yml", record));
        assertThrows(IllegalStateException.class,
                () -> new MarketTransactionJournal(root).getRecord(record.getTransactionId()));
    }

    @Test
    void forgedOrAlteredUnpersistedProposalCannotReportCurrent() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-identity").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        RecoveryProposal candidate = proposal(journal, record, "identity-key");
        assertEquals(MarketTransactionJournal.RecoveryProposalFreshness.UNAVAILABLE,
                journal.assessRecoveryProposalFreshness(candidate, System.currentTimeMillis()));
        RecoveryProposal stored = proposal(journal, record, "stored-key");
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.RECORDED,
                journal.appendRecoveryProposal(stored));
        assertEquals(MarketTransactionJournal.RecoveryProposalFreshness.CURRENT,
                journal.assessRecoveryProposalFreshness(stored, System.currentTimeMillis()));
        assertEquals(MarketTransactionJournal.RecoveryProposalFreshness.UNAVAILABLE,
                journal.assessRecoveryProposalFreshness(new RecoveryProposal(stored.getProposalId(),
                        stored.getTransactionId(), stored.getIdempotencyKey(), stored.getTargetEffectIds(),
                        stored.getTargetEffectTypes(), stored.getDisposition(), stored.getStatus(),
                        stored.getTransactionFingerprint(), stored.getObservationIds(), stored.getEvidenceIds(),
                        stored.getExpectedAmount(), stored.getExpectedQuantity(), stored.getProvenance(),
                        stored.getProposedAt(), stored.getRecordedAt() + 1, stored.getExpiresAt(), stored.getDiagnostic()),
                        System.currentTimeMillis()));
    }

    @Test
    void fingerprintIsStableAcrossReloadAndChangesReject() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-fingerprint").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        String fingerprint = journal.financialStateFingerprint(record);
        MarketTransactionRecord reloaded = new MarketTransactionJournal(root).getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(fingerprint, journal.financialStateFingerprint(reloaded));
        assertTrue(!fingerprint.equals(journal.financialStateFingerprint(changedValue(reloaded))));
        assertEquals(MarketTransactionJournal.RecoveryProposalAppendResult.REJECTED,
                journal.appendRecoveryProposal(proposalWithFingerprint(record, "changed", "not-the-current-fingerprint")));
    }

    @Test
    void viewsAreImmutableAndManualRecoveryRemainsDisabled() throws Exception {
        File root = Files.createTempDirectory("stonks-recovery-view").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        RecoveryProposal proposal = proposal(journal, record, "view-key");
        record.replaceRecoveryProposals(List.of(proposal));
        assertThrows(UnsupportedOperationException.class, () -> record.getRecoveryProposalsView().clear());
        assertTrue(!journal.resolveAfterManualRepair(record, "ignored"));
    }

    private MarketTransactionRecord blockingRecord(MarketTransactionJournal journal) {
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
        assertTrue(journal.prepareCurrentSchema(record) != null);
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0, 2));
        assertTrue(journal.markReconciliationRequired(record, "proposal test"));
        return record;
    }

    private MarketTransactionRecord changedValue(MarketTransactionRecord record) {
        record.setValue(record.getValue() + 1);
        return record;
    }

    private RecoveryProposal proposal(MarketTransactionJournal journal, MarketTransactionRecord record, String key) {
        long now = System.currentTimeMillis();
        return proposalAt(journal, record, key, now, now + 60_000);
    }

    private RecoveryProposal proposalAt(MarketTransactionJournal journal, MarketTransactionRecord record,
                                        String key, long proposedAt, long expiresAt) {
        return RecoveryProposal.create(record.getTransactionId(), key,
                List.of(record.getEffects().get(0).getEffectId()), List.of(record.getEffects().get(0).getType()),
                RecoveryProposalDisposition.NOT_VERIFIABLE, RecoveryProposalStatus.AWAITING_AUTHORITATIVE_PROOF,
                journal.financialStateFingerprint(record), List.of(), List.of(), record.getEffects().get(0).getAmount(),
                record.getEffects().get(0).getQuantity(), "operator-supplied", proposedAt, expiresAt,
                "not independently verifiable");
    }

    private RecoveryProposal proposalWithFingerprint(MarketTransactionRecord record, String key, String fingerprint) {
        MarketTransactionEffect effect = record.getEffects().get(0);
        return RecoveryProposal.create(record.getTransactionId(), key, List.of(effect.getEffectId()),
                List.of(effect.getType()), RecoveryProposalDisposition.NOT_VERIFIABLE,
                RecoveryProposalStatus.AWAITING_AUTHORITATIVE_PROOF, fingerprint, List.of(), List.of(),
                effect.getAmount(), effect.getQuantity(), "operator-supplied", System.currentTimeMillis(),
                System.currentTimeMillis() + 60_000, "not independently verifiable");
    }
}
