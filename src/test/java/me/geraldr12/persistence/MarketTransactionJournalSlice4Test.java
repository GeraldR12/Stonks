package me.geraldr12.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarketTransactionJournalSlice4Test {
    @Test
    void schema3QuoteRoundTripsExactDoubleWithoutInference() throws Exception {
        File root = Files.createTempDirectory("stonks-quote-roundtrip").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record();
        double unit = 12.3456789012345d;
        double total = unit * 7;
        record.setQuantity(7);
        record.setValue(total);
        record.setQuoteQuantity(7);
        record.setQuoteUnitSharePrice(unit);
        record.setQuoteTotalValue(total);
        assertNotNull(journal.prepareCurrentSchema(record));

        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(0, Double.compare(unit, loaded.getQuoteUnitSharePrice()));
        assertEquals(0, Double.compare(total, loaded.getQuoteTotalValue()));
    }

    @Test
    void currentSchemaManualRepairRemainsBlockedForAmbiguousMarketImpact() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(Files.createTempDirectory("stonks-impact-guard").toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0, record.getQuantity()));
        assertTrue(journal.markReconciliationRequired(record, "impact uncertain"));
        assertFalse(journal.resolveAfterManualRepair(record, "manual-repair-confirmed: external"));
        assertTrue(journal.acknowledge(record, "operator observed"));
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, record.getStatus());
        assertTrue(journal.getUnresolvedRecords().contains(record));
    }

    @Test
    void compensatedDerivationRejectsContradictoryEffects() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(Files.createTempDirectory("stonks-compensated-negative").toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT, 10, record.getValue(), record.getQuantity()));
        MarketTransactionEffect debit = record.getEffects().get(0);
        assertTrue(journal.recordEffectOutcome(record, debit.getEffectId(), MarketTransactionEffectState.CONFIRMED, "confirmed"));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 20, 0.0, record.getQuantity()));
        MarketTransactionEffect removal = record.getEffects().get(1);
        assertTrue(journal.recordEffectOutcome(record, removal.getEffectId(), MarketTransactionEffectState.DEFINITE_REJECTED, "rejected"));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_REFUND, 60, record.getValue(), record.getQuantity()));
        MarketTransactionEffect refund = record.getEffects().get(2);
        assertTrue(journal.recordEffectOutcome(record, refund.getEffectId(), MarketTransactionEffectState.CONFIRMED, "confirmed"));
        assertTrue(journal.derivesCompensated(record));
        record.getEffects().add(MarketTransactionEffect.requested(record.getTransactionId(),
                MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0, record.getQuantity()));
        assertFalse(journal.derivesCompensated(record));
    }

    @Test
    void compensatedDerivationRejectsInvalidStatesAndPayloads() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(Files.createTempDirectory("stonks-compensated-matrix").toFile());
        for (MarketTransactionEffectState addState : new MarketTransactionEffectState[]{
                MarketTransactionEffectState.REQUESTED, MarketTransactionEffectState.AMBIGUOUS,
                MarketTransactionEffectState.UNKNOWN}) {
            MarketTransactionRecord record = compensationCaseB(journal);
            MarketTransactionEffect add = record.getEffects().stream()
                    .filter(effect -> effect.getType() == MarketTransactionEffectType.PLAYER_SHARE_ADD).findFirst().orElseThrow();
            add.setState(addState);
            assertFalse(journal.derivesCompensated(record));
        }
        MarketTransactionRecord caseA = compensationCaseA(journal);
        MarketTransactionEffect debit = caseA.getEffects().stream()
                .filter(effect -> effect.getType() == MarketTransactionEffectType.ECONOMY_DEBIT).findFirst().orElseThrow();
        debit.setAmount(debit.getAmount() + 1.0);
        assertFalse(journal.derivesCompensated(caseA));
    }

    @Test
    void completedDerivationRejectsAmbiguousImpactAndRestoration() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(Files.createTempDirectory("stonks-completed-matrix").toFile());
        MarketTransactionRecord record = completeRecord(journal);
        MarketTransactionEffect impact = record.getEffects().stream()
                .filter(effect -> effect.getType() == MarketTransactionEffectType.MARKET_IMPACT).findFirst().orElseThrow();
        impact.setState(MarketTransactionEffectState.AMBIGUOUS);
        assertFalse(journal.derivesCompleted(record));
        record = completeRecord(journal);
        record.getEffects().add(MarketTransactionEffect.requested(record.getTransactionId(),
                MarketTransactionEffectType.COMPANY_SHARE_RESTORATION, 40, 0.0, record.getQuantity()));
        assertFalse(journal.derivesCompleted(record));
    }

    private MarketTransactionRecord compensationCaseA(MarketTransactionJournal journal) throws Exception {
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.ECONOMY_DEBIT, 10, record.getValue(), MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 20, 0.0, MarketTransactionEffectState.DEFINITE_REJECTED);
        effect(journal, record, MarketTransactionEffectType.ECONOMY_REFUND, 60, record.getValue(), MarketTransactionEffectState.CONFIRMED);
        return record;
    }

    private MarketTransactionRecord compensationCaseB(MarketTransactionJournal journal) throws Exception {
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.ECONOMY_DEBIT, 10, record.getValue(), MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 20, 0.0, MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_ADD, 30, 0.0, MarketTransactionEffectState.DEFINITE_REJECTED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_RESTORATION, 40, 0.0, MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.ECONOMY_REFUND, 60, record.getValue(), MarketTransactionEffectState.CONFIRMED);
        return record;
    }

    private MarketTransactionRecord completeRecord(MarketTransactionJournal journal) throws Exception {
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.ECONOMY_DEBIT, 10, record.getValue(), MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 20, 0.0, MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_ADD, 30, 0.0, MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0, MarketTransactionEffectState.CONFIRMED);
        return record;
    }

    private void effect(MarketTransactionJournal journal, MarketTransactionRecord record,
                        MarketTransactionEffectType type, int sequence, double amount,
                        MarketTransactionEffectState state) {
        assertTrue(journal.requestEffect(record, type, sequence, amount, record.getQuantity()));
        MarketTransactionEffect effect = record.getEffects().stream().filter(candidate -> candidate.getType() == type).findFirst().orElseThrow();
        assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(), state, "test"));
    }

    @Test
    void currentSchemaAbortRejectsLaterEffect() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(Files.createTempDirectory("stonks-abort-contract").toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT, 10, record.getValue(), record.getQuantity()));
        MarketTransactionEffect debit = record.getEffects().get(0);
        assertTrue(journal.recordEffectOutcome(record, debit.getEffectId(), MarketTransactionEffectState.DEFINITE_REJECTED, "rejected"));
        record.getEffects().add(MarketTransactionEffect.requested(record.getTransactionId(),
                MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 20, 0.0, record.getQuantity()));
        assertFalse(journal.abort(record, "rejected"));
    }

    @Test
    void schema2HistoricalRecordRemainsReadableWithoutSchema3Inference() throws Exception {
        File root = Files.createTempDirectory("stonks-schema2").toFile();
        MarketTransactionRecord record = record();
        record.setSchemaVersion(MarketTransactionRecord.LEGACY_SCHEMA_VERSION);
        record.setQuoteCompanyId(null);
        record.setQuotePlayerUuid(null);
        record.setQuoteQuantity(null);
        record.setQuoteUnitSharePrice(null);
        record.setQuoteTotalValue(null);
        record.setQuoteTimestamp(null);
        record.setStatus(MarketTransactionStatus.PREPARED);
        record.ensureEffects();
        record.getEffects().add(MarketTransactionEffect.requested(record.getTransactionId(),
                MarketTransactionEffectType.ECONOMY_DEBIT, 1, -10.0, 0));
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        File transactions = new File(root, "transactions");
        assertTrue(transactions.mkdirs());
        mapper.writeValue(new File(transactions, record.getTransactionId() + ".yml"), record);

        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionRecord.LEGACY_SCHEMA_VERSION, loaded.getSchemaVersion());
        assertNull(loaded.getQuoteUnitSharePrice());
        assertNull(loaded.getQuoteTotalValue());
        assertFalse(loaded.isCurrentSchema());
    }

    @Test
    void duplicateEffectsAndMalformedAbortsFailClosed() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-duplicate-effects").toFile());

        MarketTransactionRecord completed = completeRecord(journal);
        MarketTransactionEffect duplicateImpact = MarketTransactionEffect.requested(
                completed.getTransactionId(), MarketTransactionEffectType.MARKET_IMPACT, 70,
                0.0, completed.getQuantity());
        duplicateImpact.setState(MarketTransactionEffectState.CONFIRMED);
        completed.getEffects().add(duplicateImpact);
        assertFalse(journal.derivesCompleted(completed));

        MarketTransactionRecord compensated = compensationCaseB(journal);
        MarketTransactionEffect duplicateRefund = MarketTransactionEffect.requested(
                compensated.getTransactionId(), MarketTransactionEffectType.ECONOMY_REFUND, 60,
                compensated.getValue(), compensated.getQuantity());
        duplicateRefund.setState(MarketTransactionEffectState.CONFIRMED);
        compensated.getEffects().add(duplicateRefund);
        assertFalse(journal.derivesCompensated(compensated));

        MarketTransactionRecord aborted = record();
        assertNotNull(journal.prepareCurrentSchema(aborted));
        MarketTransactionEffect debit = MarketTransactionEffect.requested(
                aborted.getTransactionId(), MarketTransactionEffectType.ECONOMY_DEBIT, 10,
                aborted.getValue(), aborted.getQuantity());
        debit.setState(MarketTransactionEffectState.DEFINITE_REJECTED);
        aborted.getEffects().add(debit);
        MarketTransactionEffect duplicateDebit = MarketTransactionEffect.requested(
                aborted.getTransactionId(), MarketTransactionEffectType.ECONOMY_DEBIT, 10,
                aborted.getValue(), aborted.getQuantity());
        duplicateDebit.setState(MarketTransactionEffectState.DEFINITE_REJECTED);
        aborted.getEffects().add(duplicateDebit);
        assertFalse(journal.derivesAborted(aborted));

        aborted.getEffects().clear();
        MarketTransactionEffect wrongAmount = MarketTransactionEffect.requested(
                aborted.getTransactionId(), MarketTransactionEffectType.ECONOMY_DEBIT, 10,
                aborted.getValue() + 1.0, aborted.getQuantity());
        wrongAmount.setState(MarketTransactionEffectState.DEFINITE_REJECTED);
        aborted.getEffects().add(wrongAmount);
        assertFalse(journal.derivesAborted(aborted));
    }

    @Test
    void schema3PrepareRejectsZeroQuantityQuote() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-zero-quote").toFile());
        MarketTransactionRecord record = record();
        record.setQuantity(0);
        record.setQuoteQuantity(0);
        record.setValue(0.0);
        record.setQuoteTotalValue(0.0);
        assertNull(journal.prepareCurrentSchema(record));
    }

    private MarketTransactionRecord record() {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(UUID.randomUUID().toString());
        record.setOperation("BUY");
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
