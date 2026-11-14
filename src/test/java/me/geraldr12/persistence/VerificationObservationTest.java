package me.geraldr12.persistence;

import me.geraldr12.data.sources.yml.YmlDataSourceImpl;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationObservationTest {
    @Test
    void appendSurvivesIndependentReloadWithoutChangingFinancialState() throws Exception {
        File root = Files.createTempDirectory("stonks-verification-observation").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        MarketTransactionStatus status = record.getStatus();
        List<MarketTransactionEffect> effects = List.copyOf(record.getEffects());
        String effectId = effects.get(0).getEffectId();
        VerificationObservation observation = observation(record, effectId, "request-1", 1000L);

        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.RECORDED,
                journal.appendVerificationObservation(observation));
        MarketTransactionRecord reloaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(1, reloaded.getVerificationObservationsView().size());
        assertEquals(observation.getObservationId(),
                reloaded.getVerificationObservationsView().get(0).getObservationId());
        assertEquals(status, reloaded.getStatus());
        assertEquals(effects, reloaded.getEffects());
        assertTrue(new MarketTransactionJournal(root).isBlocking(reloaded));
        assertTrue(reloaded.getReconciliationEvidenceView().isEmpty());
        assertTrue(reloaded.getReconciliationAuditView().isEmpty());
    }

    @Test
    void independentIdenticalRequestIsIdempotentAndPreservesServerFields() throws Exception {
        File root = Files.createTempDirectory("stonks-verification-idempotency").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        String effectId = record.getEffects().get(0).getEffectId();
        VerificationObservation first = observation(record, effectId, "same-key", 2000L);
        VerificationObservation second = new VerificationObservation(
                UUID.randomUUID().toString(), first.getTransactionId(), first.getEffectId(),
                first.getExpectedEffectType(), first.getSource(), first.getAssessment(),
                first.getExternalReference(), first.getProvenance(), first.getObservationTimestamp(),
                first.getRecordedAt() + 1000, first.getIdempotencyKey(), first.getDiagnostic());

        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.RECORDED,
                journal.appendVerificationObservation(first));
        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.ALREADY_RECORDED,
                new MarketTransactionJournal(root).appendVerificationObservation(second));
        VerificationObservation stored = new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow().getVerificationObservationsView().get(0);
        assertEquals(first.getObservationId(), stored.getObservationId());
        assertEquals(first.getRecordedAt(), stored.getRecordedAt());
        assertEquals(1, new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow().getVerificationObservationsView().size());
    }

    @Test
    void sameKeyChangedSemanticPayloadConflictsIncludingObservationTimestamp() throws Exception {
        File root = Files.createTempDirectory("stonks-verification-conflict").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        String effectId = record.getEffects().get(0).getEffectId();
        VerificationObservation first = observation(record, effectId, "same-key", 3000L);
        VerificationObservation changed = new VerificationObservation(
                UUID.randomUUID().toString(), first.getTransactionId(), first.getEffectId(),
                first.getExpectedEffectType(), first.getSource(), first.getAssessment(),
                first.getExternalReference(), first.getProvenance(), first.getObservationTimestamp() + 1,
                System.currentTimeMillis(), first.getIdempotencyKey(), first.getDiagnostic());

        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.RECORDED,
                journal.appendVerificationObservation(first));
        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.CONFLICT,
                new MarketTransactionJournal(root).appendVerificationObservation(changed));
    }

    @Test
    void crossEffectKeyReuseIsNotIdempotent() throws Exception {
        File root = Files.createTempDirectory("stonks-verification-cross-effect").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        MarketTransactionEffect secondEffect = MarketTransactionEffect.requested(record.getTransactionId(),
                MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0, 2);
        record.setEffects(List.of(record.getEffects().get(0), secondEffect));
        assertTrue(journal.markReconciliationRequired(record, "two effects"));
        VerificationObservation first = observation(record, record.getEffects().get(0).getEffectId(), "cross-key", 4000L);
        VerificationObservation second = observation(record, secondEffect.getEffectId(), "cross-key", 4000L);

        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.RECORDED,
                journal.appendVerificationObservation(first));
        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.REJECTED,
                new MarketTransactionJournal(root).appendVerificationObservation(second));
    }

    @Test
    void concurrentSameKeyStoresOneObservation() throws Exception {
        File root = Files.createTempDirectory("stonks-verification-concurrent").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        String effectId = record.getEffects().get(0).getEffectId();
        VerificationObservation first = observation(record, effectId, "concurrent-key", 5000L);
        VerificationObservation second = new VerificationObservation(
                UUID.randomUUID().toString(), first.getTransactionId(), first.getEffectId(),
                first.getExpectedEffectType(), first.getSource(), first.getAssessment(),
                first.getExternalReference(), first.getProvenance(), first.getObservationTimestamp(),
                first.getRecordedAt() + 1, first.getIdempotencyKey(), first.getDiagnostic());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MarketTransactionJournal.VerificationObservationAppendResult> left = executor.submit(
                    () -> new MarketTransactionJournal(root).appendVerificationObservation(first));
            Future<MarketTransactionJournal.VerificationObservationAppendResult> right = executor.submit(
                    () -> new MarketTransactionJournal(root).appendVerificationObservation(second));
            assertTrue((left.get() == MarketTransactionJournal.VerificationObservationAppendResult.RECORDED
                    && right.get() == MarketTransactionJournal.VerificationObservationAppendResult.ALREADY_RECORDED)
                    || (right.get() == MarketTransactionJournal.VerificationObservationAppendResult.RECORDED
                    && left.get() == MarketTransactionJournal.VerificationObservationAppendResult.ALREADY_RECORDED));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, new MarketTransactionJournal(root).getRecord(record.getTransactionId()).orElseThrow()
                .getVerificationObservationsView().size());
    }

    @Test
    void invalidObservationAndWrongEffectTypeAreRejected() throws Exception {
        File root = Files.createTempDirectory("stonks-verification-invalid").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        String effectId = record.getEffects().get(0).getEffectId();
        VerificationObservation invalid = new VerificationObservation(
                "not-uuid", record.getTransactionId(), effectId,
                MarketTransactionEffectType.COMPANY_SHARE_ADD, VerificationObservationSource.UNAVAILABLE,
                VerificationObservationAssessment.OBSERVED_UNCONFIRMED, "ref", "untrusted", 1, 1,
                "key", "diagnostic");
        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.REJECTED,
                journal.appendVerificationObservation(invalid));
        assertEquals(0, record.getVerificationObservationsView().size());
    }

    @Test
    void persistenceFailureRollsBackAndDoesNotReportRecorded() throws Exception {
        File root = Files.createTempDirectory("stonks-verification-failure").toFile();
        MarketTransactionRecord record = blockingRecord(new MarketTransactionJournal(root));
        MarketTransactionJournal failingJournal = new MarketTransactionJournal(root, attempt -> false);
        VerificationObservation observation = observation(record, record.getEffects().get(0).getEffectId(), "failure-key", 6000L);

        assertEquals(MarketTransactionJournal.VerificationObservationAppendResult.PERSISTENCE_FAILED,
                failingJournal.appendVerificationObservation(observation));
        assertTrue(record.getVerificationObservationsView().isEmpty());
        assertTrue(new MarketTransactionJournal(root).getRecord(record.getTransactionId()).orElseThrow()
                .getVerificationObservationsView().isEmpty());
    }

    @Test
    void malformedPersistedObservationFailsClosedAndViewsAreDefensive() throws Exception {
        File root = Files.createTempDirectory("stonks-verification-corrupt").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = blockingRecord(journal);
        VerificationObservation valid = observation(record, record.getEffects().get(0).getEffectId(), "view-key", 7000L);
        record.replaceVerificationObservations(List.of(valid));
        assertThrows(UnsupportedOperationException.class,
                () -> record.getVerificationObservationsView().clear());
        assertNotSame(record.getVerificationObservationsView(), record.getVerificationObservationsView());

        VerificationObservation malformed = new VerificationObservation(
                "not-uuid", valid.getTransactionId(), valid.getEffectId(), valid.getExpectedEffectType(),
                valid.getSource(), valid.getAssessment(), valid.getExternalReference(), valid.getProvenance(),
                valid.getObservationTimestamp(), valid.getRecordedAt(), "bad-key", valid.getDiagnostic());
        record.replaceVerificationObservations(List.of(malformed));
        assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(root)
                .save("transactions/" + record.getTransactionId() + ".yml", record));
        assertThrows(IllegalStateException.class,
                () -> new MarketTransactionJournal(root).getRecord(record.getTransactionId()));
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
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                10, 0.0, 2));
        assertTrue(journal.markReconciliationRequired(record, "verification required"));
        return record;
    }

    private VerificationObservation observation(MarketTransactionRecord record, String effectId,
                                                String key, long timestamp) {
        return VerificationObservation.create(record.getTransactionId(), effectId,
                MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                VerificationObservationSource.UNAVAILABLE,
                VerificationObservationAssessment.NOT_VERIFIABLE,
                "external-reference", "operator-supplied", timestamp, key, "not independently verifiable");
    }
}
