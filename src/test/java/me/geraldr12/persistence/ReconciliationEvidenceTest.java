package me.geraldr12.persistence;

import org.junit.jupiter.api.Test;
import me.geraldr12.data.sources.yml.YmlDataSourceImpl;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationEvidenceTest {
    @Test
    void validEvidenceAppendsAuditAndSurvivesReload() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, root, MarketTransactionEffectState.AMBIGUOUS);
        ReconciliationEvidence evidence = evidence(record, record.getEffects().get(0).getEffectId(), "key-1");

        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED,
                journal.appendEvidence(evidence));
        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(1, loaded.getReconciliationEvidenceView().size());
        assertEquals(1, loaded.getReconciliationAuditView().size());
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, loaded.getStatus());
    }

    @Test
    void requestedEffectCanReceiveEvidenceWithoutChangingState() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-requested").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, root, MarketTransactionEffectState.REQUESTED);
        ReconciliationEvidence evidence = evidence(record, record.getEffects().get(0).getEffectId(), "key-1");

        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED,
                journal.appendEvidence(evidence));
        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionEffectState.REQUESTED, loaded.getEffects().get(0).getState());
        assertTrue(journal.isBlocking(loaded));
    }

    @Test
    void duplicatePayloadIsIdempotentAndConflictIsRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-idempotency").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, root, MarketTransactionEffectState.AMBIGUOUS);
        String effectId = record.getEffects().get(0).getEffectId();
        ReconciliationEvidence evidence = evidence(record, effectId, "key-1");

        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED,
                journal.appendEvidence(evidence));
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.ALREADY_RECORDED,
                new MarketTransactionJournal(root).appendEvidence(evidence));
        ReconciliationEvidence conflict = ReconciliationEvidence.create(record.getTransactionId(), effectId,
                ReconciliationEvidenceType.EXTERNAL_AUDIT_EVIDENCE, "ticket-123",
                evidence.getOperatorId(), "operator", "different claim",
                evidence.getCreatedAt(), "key-1");
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.CONFLICT,
                new MarketTransactionJournal(root).appendEvidence(conflict));
    }

    @Test
    void staleEffectMutationPreservesEvidence() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-stale").toFile();
        MarketTransactionJournal first = new MarketTransactionJournal(root);
        MarketTransactionRecord retained = record(first, root, MarketTransactionEffectState.REQUESTED);
        MarketTransactionJournal second = new MarketTransactionJournal(root);
        ReconciliationEvidence evidence = evidence(retained, retained.getEffects().get(0).getEffectId(), "key-1");
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED, second.appendEvidence(evidence));

        assertTrue(first.recordEffectOutcome(retained, retained.getEffects().get(0).getEffectId(),
                MarketTransactionEffectState.AMBIGUOUS, "uncertain"));
        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(retained.getTransactionId()).orElseThrow();
        assertEquals(1, loaded.getReconciliationEvidenceView().size());
        assertEquals(1, loaded.getReconciliationAuditView().size());
        assertTrue(new MarketTransactionJournal(root).isBlocking(loaded));
    }

    @Test
    void writeFailureDoesNotPersistNewEvidence() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-failure").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, root, MarketTransactionEffectState.AMBIGUOUS);
        ReconciliationEvidence evidence = evidence(record, record.getEffects().get(0).getEffectId(), "key-1");
        MarketTransactionJournal failing = new MarketTransactionJournal(root, attempt -> false);
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.PERSISTENCE_FAILED,
                failing.appendEvidence(evidence));
        assertTrue(new MarketTransactionJournal(root).getRecord(record.getTransactionId()).orElseThrow()
                .getReconciliationEvidenceView().isEmpty());
    }

    @Test
    void concurrentDistinctEvidenceAppendsBothSurvive() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-concurrent").toFile();
        MarketTransactionJournal setup = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(setup, root, MarketTransactionEffectState.AMBIGUOUS);
        String effectId = record.getEffects().get(0).getEffectId();
        ReconciliationEvidence first = evidence(record, effectId, "key-a");
        ReconciliationEvidence second = evidence(record, effectId, "key-b");
        CountDownLatch ready = new CountDownLatch(2);
        MarketTransactionJournal leftJournal = new MarketTransactionJournal(root);
        MarketTransactionJournal rightJournal = new MarketTransactionJournal(root);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MarketTransactionJournal.EvidenceAppendResult> left = executor.submit(() -> {
                ready.countDown();
                ready.await();
                return leftJournal.appendEvidence(first);
            });
            Future<MarketTransactionJournal.EvidenceAppendResult> right = executor.submit(() -> {
                ready.countDown();
                ready.await();
                return rightJournal.appendEvidence(second);
            });
            assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED, left.get());
            assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED, right.get());
        } finally {
            executor.shutdownNow();
        }
        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(2, loaded.getReconciliationEvidenceView().size());
        assertEquals(2, loaded.getReconciliationAuditView().size());
    }

    @Test
    void generalWholeRecordSavePrimitiveIsNotExposed() {
        assertTrue(java.util.Arrays.stream(MarketTransactionJournal.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("save")));
    }

    @Test
    void staleOutcomeCannotEraseNewerEvidenceOrStatus() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-stale-save").toFile();
        MarketTransactionJournal first = new MarketTransactionJournal(root);
        MarketTransactionRecord stale = record(first, root, MarketTransactionEffectState.REQUESTED);
        String effectId = stale.getEffects().get(0).getEffectId();
        MarketTransactionJournal second = new MarketTransactionJournal(root);
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED,
                second.appendEvidence(evidence(stale, effectId, "stale-save-key")));
        assertTrue(first.recordEffectOutcome(stale, effectId,
                MarketTransactionEffectState.AMBIGUOUS, "provider uncertain"));
        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(stale.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionEffectState.AMBIGUOUS, loaded.getEffects().get(0).getState());
        assertEquals(1, loaded.getReconciliationEvidenceView().size());
        assertEquals(1, loaded.getReconciliationAuditView().size());
        assertTrue(new MarketTransactionJournal(root).isBlocking(loaded));
    }

    @Test
    void conflictingOutcomeCannotReplaceFirstOutcome() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-conflict").toFile();
        MarketTransactionJournal first = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(first, root, MarketTransactionEffectState.REQUESTED);
        String effectId = record.getEffects().get(0).getEffectId();
        MarketTransactionJournal second = new MarketTransactionJournal(root);
        assertTrue(first.recordEffectOutcome(record, effectId,
                MarketTransactionEffectState.CONFIRMED, "confirmed"));
        assertFalse(second.recordEffectOutcome(record, effectId,
                MarketTransactionEffectState.AMBIGUOUS, "conflicting"));
        assertEquals(MarketTransactionEffectState.CONFIRMED,
                new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                        .orElseThrow().getEffects().get(0).getState());
    }

    @Test
    void publicEvidenceViewCannotMutateRecord() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-view").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, root, MarketTransactionEffectState.AMBIGUOUS);
        assertThrows(UnsupportedOperationException.class,
                () -> record.getReconciliationEvidenceView().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> record.getReconciliationAuditView().clear());
    }

    @Test
    void concurrentSameKeyAcceptsOnlyOneAppend() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-same-key").toFile();
        MarketTransactionJournal setup = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(setup, root, MarketTransactionEffectState.AMBIGUOUS);
        ReconciliationEvidence evidence = evidence(record, record.getEffects().get(0).getEffectId(), "same-key");
        CountDownLatch ready = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MarketTransactionJournal.EvidenceAppendResult> first = executor.submit(() -> {
                ready.countDown(); ready.await();
                return new MarketTransactionJournal(root).appendEvidence(evidence);
            });
            Future<MarketTransactionJournal.EvidenceAppendResult> second = executor.submit(() -> {
                ready.countDown(); ready.await();
                return new MarketTransactionJournal(root).appendEvidence(evidence);
            });
            assertTrue((first.get() == MarketTransactionJournal.EvidenceAppendResult.RECORDED
                    && second.get() == MarketTransactionJournal.EvidenceAppendResult.ALREADY_RECORDED)
                    || (second.get() == MarketTransactionJournal.EvidenceAppendResult.RECORDED
                    && first.get() == MarketTransactionJournal.EvidenceAppendResult.ALREADY_RECORDED));
        } finally {
            executor.shutdownNow();
        }
        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(1, loaded.getReconciliationEvidenceView().size());
        assertEquals(1, loaded.getReconciliationAuditView().size());
    }

    @Test
    void evidenceAndStatusConcurrencyPreservesValidBlockingRecord() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-status").toFile();
        MarketTransactionJournal setup = new MarketTransactionJournal(root);
        MarketTransactionRecord seed = record(setup, root, MarketTransactionEffectState.REQUESTED);
        String effectId = seed.getEffects().get(0).getEffectId();
        MarketTransactionJournal evidenceJournal = new MarketTransactionJournal(root);
        MarketTransactionJournal statusJournal = new MarketTransactionJournal(root);
        MarketTransactionRecord evidenceRecord = evidenceJournal.getRecord(seed.getTransactionId()).orElseThrow();
        MarketTransactionRecord statusRecord = statusJournal.getRecord(seed.getTransactionId()).orElseThrow();
        ReconciliationEvidence evidence = evidence(evidenceRecord, effectId, "status-key");
        CountDownLatch ready = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MarketTransactionJournal.EvidenceAppendResult> evidenceResult = executor.submit(() -> {
                ready.countDown();
                ready.await();
                return evidenceJournal.appendEvidence(evidence);
            });
            Future<Boolean> statusResult = executor.submit(() -> {
                ready.countDown();
                ready.await();
                return statusJournal.markReconciliationRequired(statusRecord, "operator review");
            });
            MarketTransactionJournal.EvidenceAppendResult evidenceOutcome = evidenceResult.get();
            boolean statusOutcome = statusResult.get();
            assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED, evidenceOutcome);
            assertTrue(statusOutcome);
        } finally {
            executor.shutdownNow();
        }
        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(seed.getTransactionId()).orElseThrow();
        assertEquals(1, loaded.getReconciliationEvidenceView().size());
        assertEquals(1, loaded.getReconciliationAuditView().size());
        assertEquals(MarketTransactionEffectState.REQUESTED, loaded.getEffects().get(0).getState());
        assertTrue(new MarketTransactionJournal(root).isBlocking(loaded));
    }

    @Test
    void committedUnresolvedMarketImpactAcceptsInvestigationEvidence() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-committed-impact").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, root, MarketTransactionEffectState.REQUESTED);
        String playerRemovalId = record.getEffects().get(0).getEffectId();
        assertTrue(journal.recordEffectOutcome(record, playerRemovalId,
                MarketTransactionEffectState.CONFIRMED, "confirmed"));
        for (MarketTransactionEffectType type : new MarketTransactionEffectType[]{
                MarketTransactionEffectType.COMPANY_SHARE_ADD,
                MarketTransactionEffectType.ECONOMY_CREDIT}) {
            int sequence = type == MarketTransactionEffectType.COMPANY_SHARE_ADD ? 20 : 30;
            double amount = type == MarketTransactionEffectType.ECONOMY_CREDIT ? record.getValue() : 0.0;
            assertTrue(journal.requestEffect(record, type, sequence, amount, record.getQuantity()));
            MarketTransactionEffect effect = record.getEffects().get(record.getEffects().size() - 1);
            assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(),
                    MarketTransactionEffectState.CONFIRMED, "confirmed"));
        }
        assertEquals(MarketTransactionStatus.COMMITTED, record.getStatus());
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.MARKET_IMPACT,
                70, 0.0, record.getQuantity()));
        String impactId = record.getEffects().get(record.getEffects().size() - 1).getEffectId();
        assertEquals(MarketTransactionEffectState.REQUESTED,
                record.getEffects().get(record.getEffects().size() - 1).getState());

        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED,
                journal.appendEvidence(evidence(record, impactId, "market-impact-key")));

        MarketTransactionRecord reloaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.COMMITTED, reloaded.getStatus());
        assertEquals(1, reloaded.getReconciliationEvidenceView().size());
        assertEquals(1, reloaded.getReconciliationAuditView().size());
        assertEquals(MarketTransactionEffectState.REQUESTED,
                reloaded.getEffects().stream().filter(effect -> impactId.equals(effect.getEffectId()))
                        .findFirst().orElseThrow().getState());
        assertEquals(MarketTransactionEffectState.CONFIRMED,
                reloaded.getEffects().stream().filter(effect -> effect.getType() == MarketTransactionEffectType.ECONOMY_CREDIT)
                        .findFirst().orElseThrow().getState());
        assertTrue(new MarketTransactionJournal(root).isBlocking(reloaded));
    }

    @Test
    void malformedPersistedReconciliationDataIsRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-corruption").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord valid = record(journal, root, MarketTransactionEffectState.AMBIGUOUS);
        ReconciliationEvidence stored = evidence(valid, valid.getEffects().get(0).getEffectId(), "valid-key");
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.RECORDED, journal.appendEvidence(stored));
        MarketTransactionRecord baseline = new MarketTransactionJournal(root)
                .getRecord(valid.getTransactionId()).orElseThrow();
        assertEquals(1, baseline.getReconciliationEvidenceView().size());
        assertEquals(1, baseline.getReconciliationAuditView().size());

        List<java.util.function.Consumer<MarketTransactionRecord>> corruptions = List.of(
                record -> record.replaceReconciliationData(record.getReconciliationEvidenceView(),
                        List.<ReconciliationAuditEntry>of()),
                record -> record.replaceReconciliationData(record.getReconciliationEvidenceView(),
                        List.of(record.getReconciliationAuditView().get(0),
                                new ReconciliationAuditEntry(UUID.randomUUID().toString(),
                                        valid.getTransactionId(), stored.getEvidenceId(), "EVIDENCE_RECORDED",
                                        stored.getOperatorId(), stored.getCreatedAt(), stored.getIdempotencyKey()))),
                record -> record.replaceReconciliationData(record.getReconciliationEvidenceView(),
                        List.of(new ReconciliationAuditEntry(UUID.randomUUID().toString(),
                                valid.getTransactionId(), UUID.randomUUID().toString(), "EVIDENCE_RECORDED",
                                stored.getOperatorId(), stored.getCreatedAt(), stored.getIdempotencyKey()))),
                record -> record.replaceReconciliationData(record.getReconciliationEvidenceView(),
                        List.<ReconciliationAuditEntry>of()),
                record -> record.replaceReconciliationData(
                        List.<ReconciliationEvidence>of(new ReconciliationEvidence(UUID.randomUUID().toString(), valid.getTransactionId(),
                                stored.getEffectId(), stored.getEvidenceType(), stored.getExternalReference(),
                                stored.getOperatorId(), stored.getOperatorName(), stored.getStatement(),
                                stored.getCreatedAt(), stored.getIdempotencyKey())),
                        record.getReconciliationAuditView()),
                record -> record.replaceReconciliationData(
                        List.<ReconciliationEvidence>of(
                                new ReconciliationEvidence(stored.getEvidenceId(), valid.getTransactionId(),
                                        stored.getEffectId(), stored.getEvidenceType(), stored.getExternalReference(),
                                        stored.getOperatorId(), stored.getOperatorName(), stored.getStatement(),
                                        stored.getCreatedAt(), "other-key"), stored),
                        record.getReconciliationAuditView()),
                record -> record.replaceReconciliationData(
                        List.of(new ReconciliationEvidence(stored.getEvidenceId(), UUID.randomUUID().toString(),
                                stored.getEffectId(), stored.getEvidenceType(), stored.getExternalReference(),
                                stored.getOperatorId(), stored.getOperatorName(), stored.getStatement(),
                                stored.getCreatedAt(), stored.getIdempotencyKey())),
                        record.getReconciliationAuditView()),
                record -> record.replaceReconciliationData(
                        List.<ReconciliationEvidence>of(new ReconciliationEvidence(stored.getEvidenceId(), valid.getTransactionId(),
                                UUID.randomUUID().toString(), stored.getEvidenceType(), stored.getExternalReference(),
                                stored.getOperatorId(), stored.getOperatorName(), stored.getStatement(),
                                stored.getCreatedAt(), stored.getIdempotencyKey())),
                        record.getReconciliationAuditView()),
                record -> record.replaceReconciliationData(record.getReconciliationEvidenceView(),
                        List.of(new ReconciliationAuditEntry(UUID.randomUUID().toString(), valid.getTransactionId(),
                                stored.getEvidenceId(), "UNSUPPORTED_ACTION", stored.getOperatorId(),
                                stored.getCreatedAt(), stored.getIdempotencyKey()))),
                record -> record.replaceReconciliationData(record.getReconciliationEvidenceView(),
                        List.of(new ReconciliationAuditEntry(UUID.randomUUID().toString(), valid.getTransactionId(),
                                stored.getEvidenceId(), "EVIDENCE_RECORDED", UUID.randomUUID().toString(),
                                stored.getCreatedAt(), stored.getIdempotencyKey()))),
                record -> record.replaceReconciliationData(record.getReconciliationEvidenceView(),
                        List.of(new ReconciliationAuditEntry(UUID.randomUUID().toString(), valid.getTransactionId(),
                                stored.getEvidenceId(), "EVIDENCE_RECORDED", stored.getOperatorId(),
                                stored.getCreatedAt(), "other-key"))),
                record -> record.replaceReconciliationData(record.getReconciliationEvidenceView(),
                        List.of(new ReconciliationAuditEntry(UUID.randomUUID().toString(), valid.getTransactionId(),
                                stored.getEvidenceId(), "EVIDENCE_RECORDED", stored.getOperatorId(),
                                stored.getCreatedAt() + 1, stored.getIdempotencyKey()))),
                record -> {
                    List<ReconciliationEvidence> evidence = new ArrayList<>(record.getReconciliationEvidenceView());
                    evidence.set(0, null);
                    record.replaceReconciliationData(evidence, record.getReconciliationAuditView());
                },
                record -> {
                    List<ReconciliationAuditEntry> audit = new ArrayList<>(record.getReconciliationAuditView());
                    audit.set(0, null);
                    record.replaceReconciliationData(record.getReconciliationEvidenceView(), audit);
                }
        );
        for (int index = 0; index < corruptions.size(); index++) {
            MarketTransactionRecord malformed = new MarketTransactionJournal(root)
                    .getRecord(valid.getTransactionId()).orElseThrow();
            corruptions.get(index).accept(malformed);
            File fixtureRoot = Files.createTempDirectory("stonks-corrupt-fixture-" + index).toFile();
            YmlDataSourceImpl<MarketTransactionRecord> source = new YmlDataSourceImpl<>(fixtureRoot);
            assertTrue(source.save("transactions/" + valid.getTransactionId() + ".yml", malformed));
            assertThrows(IllegalStateException.class,
                    () -> new MarketTransactionJournal(fixtureRoot).getRecord(valid.getTransactionId()));
        }
    }

    @Test
    void invalidAndUnknownEvidenceIsRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-evidence-invalid").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record(journal, root, MarketTransactionEffectState.AMBIGUOUS);
        ReconciliationEvidence invalid = evidence(record, record.getEffects().get(0).getEffectId(), "key-1");
        ReconciliationEvidence malformed = ReconciliationEvidence.create(record.getTransactionId(),
                invalid.getEffectId(), invalid.getEvidenceType(), invalid.getExternalReference(),
                "not-a-uuid", invalid.getOperatorName(), invalid.getStatement(),
                invalid.getCreatedAt(), invalid.getIdempotencyKey());
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.REJECTED,
                journal.appendEvidence(malformed));
        ReconciliationEvidence unknown = evidence(record, UUID.randomUUID().toString(), "key-2");
        assertEquals(MarketTransactionJournal.EvidenceAppendResult.REJECTED,
                journal.appendEvidence(unknown));
    }

    private MarketTransactionRecord record(MarketTransactionJournal journal, File root,
                                           MarketTransactionEffectState state) {
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
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                10, 0.0, 2));
        if (state != MarketTransactionEffectState.REQUESTED) {
            assertTrue(journal.recordEffectOutcome(record, record.getEffects().get(0).getEffectId(),
                    state, "uncertain effect"));
            assertTrue(journal.markReconciliationRequired(record, "uncertain effect"));
        }
        return record;
    }

    private ReconciliationEvidence evidence(MarketTransactionRecord record,
                                             String effectId,
                                             String idempotencyKey) {
        return ReconciliationEvidence.create(record.getTransactionId(), effectId,
                ReconciliationEvidenceType.EXTERNAL_AUDIT_EVIDENCE, "ticket-123",
                UUID.randomUUID().toString(), "operator", "investigation recorded",
                System.currentTimeMillis(), idempotencyKey);
    }
}
