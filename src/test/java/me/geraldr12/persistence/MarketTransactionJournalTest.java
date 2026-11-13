package me.geraldr12.persistence;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import static org.mockito.Mockito.spy;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketTransactionJournalTest {

    @Test
    void legacyManualRepairFailsClosedForPersistedSchema2Record() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-manual-repair-schema2").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepare(record));
        assertTrue(journal.markReconciliationRequired(record, "legacy outcome unresolved"));
        MarketTransactionRecord before = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();

        assertFalse(journal.resolveAfterManualRepair(record,
                "manual-repair-confirmed: external receipt"));

        MarketTransactionRecord reloaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(before.getStatus(), reloaded.getStatus());
        assertEquals(before.getEffects(), reloaded.getEffects());
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, reloaded.getStatus());
        assertTrue(new MarketTransactionJournal(root).isBlocking(reloaded));
        assertTrue(new MarketTransactionJournal(root).getUnresolvedRecords().stream()
                .anyMatch(candidate -> record.getTransactionId().equals(candidate.getTransactionId())));
    }

    @Test
    void blockingSemanticsAreStatusBasedForCurrentAndLegacyRecords() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-blocking").toFile());
        for (MarketTransactionStatus status : new MarketTransactionStatus[]{
                MarketTransactionStatus.PREPARED,
                MarketTransactionStatus.IN_PROGRESS,
                MarketTransactionStatus.COMMITTED,
                MarketTransactionStatus.COMPENSATING,
                MarketTransactionStatus.RECONCILIATION_REQUIRED}) {
            MarketTransactionRecord current = record();
            current.setSchemaVersion(MarketTransactionRecord.CURRENT_SCHEMA_VERSION);
            current.setStatus(status);
            assertTrue(journal.isBlocking(current), status.toString());
        }
        for (MarketTransactionStatus status : new MarketTransactionStatus[]{
                MarketTransactionStatus.COMPLETED,
                MarketTransactionStatus.COMPENSATED,
                MarketTransactionStatus.ABORTED}) {
            MarketTransactionRecord current = record();
            current.setSchemaVersion(MarketTransactionRecord.CURRENT_SCHEMA_VERSION);
            current.setStatus(status);
            assertFalse(journal.isBlocking(current), status.toString());
        }
        for (MarketTransactionStatus status : new MarketTransactionStatus[]{
                MarketTransactionStatus.PREPARED,
                MarketTransactionStatus.ECONOMY_APPLIED,
                MarketTransactionStatus.ABORTED,
                MarketTransactionStatus.RECONCILIATION_REQUIRED,
                MarketTransactionStatus.STATE_COMMITTED,
                MarketTransactionStatus.MARKET_IMPACT_STARTED,
                MarketTransactionStatus.MARKET_IMPACT_APPLIED}) {
            MarketTransactionRecord legacy = record();
            legacy.setStatus(status);
            assertTrue(journal.isBlocking(legacy), status.toString());
        }
        MarketTransactionRecord legacyCompleted = record();
        legacyCompleted.setStatus(MarketTransactionStatus.COMPLETED);
        assertFalse(journal.isBlocking(legacyCompleted));

        MarketTransactionRecord migratedCompleted = record();
        migratedCompleted.setSchemaVersion(MarketTransactionRecord.CURRENT_SCHEMA_VERSION);
        migratedCompleted.setLegacyMigrated(true);
        migratedCompleted.setStatus(MarketTransactionStatus.COMPLETED);
        assertFalse(journal.isBlocking(migratedCompleted));
    }

    @Test
    void malformedCompensatedRecordsFailClosedWhenRequiredEffectsAreMissing() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-compensated").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record();
        record.setSchemaVersion(MarketTransactionRecord.CURRENT_SCHEMA_VERSION);
        record.setStatus(MarketTransactionStatus.COMPENSATED);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        File transactions = new File(root, "transactions");
        assertTrue(transactions.mkdirs());
        mapper.writeValue(new File(transactions, record.getTransactionId() + ".yml"), record);
        assertThrows(IllegalStateException.class,
                () -> journal.getRecord(record.getTransactionId()));
    }

    @Test
    void transitionsAreStrictAndReconciliationBlocks() throws Exception {
        File root = Files.createTempDirectory("stonks-journal").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record();

        assertNotNull(journal.prepareCurrentSchema(record));
        assertFalse(journal.markStateCommitted(record));
        assertTrue(journal.markEconomyApplied(record));
        assertTrue(journal.markStateCommitted(record));
        assertTrue(journal.markMarketImpactStarted(record));
        assertTrue(journal.markReconciliationRequired(record, "impact uncertain"));
        assertFalse(journal.markMarketImpactApplied(record));
        assertTrue(journal.getUnresolvedRecords().contains(record));
        assertFalse(journal.acknowledge(record, " "));
        assertTrue(journal.acknowledge(record, "operator verified state"));
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, record.getStatus());
        assertTrue(journal.getUnresolvedRecords().contains(record));
        assertFalse(journal.resolveAfterManualRepair(record, "manual-repair-confirmed:external receipt 123"));
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, record.getStatus());
        assertTrue(journal.getUnresolvedRecords().contains(record));
    }

    @Test
    void confirmedPreparedFailureAbortsAndDoesNotBlock() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-abort").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record();

        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                10, record.getValue(), record.getQuantity()));
        MarketTransactionEffect debit = record.getEffects().get(0);
        assertTrue(journal.recordEffectOutcome(record, debit.getEffectId(),
                MarketTransactionEffectState.DEFINITE_REJECTED, "provider rejected withdrawal"));
        assertTrue(journal.abort(record, "provider rejected withdrawal"));
        assertEquals(MarketTransactionStatus.ABORTED, record.getStatus());
        assertTrue(journal.getUnresolvedRecords().isEmpty());
    }

    @Test
    void effectRequestAndOutcomeArePersisted() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-effects").toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                10, record.getValue(), record.getQuantity()));
        String effectId = record.getEffects().get(0).getEffectId();
        assertTrue(journal.recordEffectOutcome(record, effectId,
                MarketTransactionEffectState.CONFIRMED, null));
        MarketTransactionRecord loaded = journal.getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionEffectState.CONFIRMED,
                loaded.getEffects().get(0).getState());
    }

    @Test
    void legacyPreparedAndEconomyAppliedRequireReconciliation() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-legacy").toFile());
        MarketTransactionRecord prepared = record();
        prepared.setStatus(MarketTransactionStatus.PREPARED);
        assertNotNull(journal.migrateLegacyRecord(prepared));
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, prepared.getStatus());
        assertEquals(MarketTransactionEffectState.UNKNOWN,
                prepared.getEffects().get(0).getState());

        MarketTransactionRecord applied = record();
        applied.setStatus(MarketTransactionStatus.ECONOMY_APPLIED);
        assertNotNull(journal.migrateLegacyRecord(applied));
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, applied.getStatus());
        assertEquals(MarketTransactionEffectState.CONFIRMED,
                applied.getEffects().get(0).getState());
        assertEquals(MarketTransactionEffectState.UNKNOWN,
                applied.getEffects().get(1).getState());
        assertEquals(MarketTransactionEffectState.UNKNOWN,
                applied.getEffects().get(2).getState());
    }

    @Test
    void currentEffectsDeriveCommittedWithoutLegacyMarker() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-committed").toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        for (MarketTransactionEffectType type : new MarketTransactionEffectType[]{
                MarketTransactionEffectType.ECONOMY_DEBIT,
                MarketTransactionEffectType.COMPANY_SHARE_REMOVAL,
                MarketTransactionEffectType.PLAYER_SHARE_ADD}) {
            assertTrue(journal.requestEffect(record, type,
                    type == MarketTransactionEffectType.ECONOMY_DEBIT ? 10
                            : type == MarketTransactionEffectType.COMPANY_SHARE_REMOVAL ? 20 : 30,
                    type == MarketTransactionEffectType.ECONOMY_DEBIT ? record.getValue() : 0.0, record.getQuantity()));
            MarketTransactionEffect effect = record.getEffects().get(record.getEffects().size() - 1);
            assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(),
                    MarketTransactionEffectState.CONFIRMED, null));
        }
        assertTrue(record.getEffects().stream().allMatch(effect ->
                effect.getState() == MarketTransactionEffectState.CONFIRMED));
        assertEquals(MarketTransactionStatus.COMMITTED, record.getStatus());
        assertTrue(record.getEffects().stream().noneMatch(effect ->
                effect.getType() == MarketTransactionEffectType.COMPANY_SHARE_RESTORATION
                        || effect.getType() == MarketTransactionEffectType.PLAYER_SHARE_RESTORATION
                        || effect.getType() == MarketTransactionEffectType.ECONOMY_REFUND));
        assertFalse(journal.companyStateSafeForRefund(record));
        assertFalse(journal.playerStateSafeForRefund(record));
    }

    @Test
    void ambiguousPlayerAddIsNotSafeForRefund() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-safety").toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_ADD,
                30, 0.0, record.getQuantity()));
        MarketTransactionEffect effect = record.getEffects().get(0);
        assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(),
                MarketTransactionEffectState.AMBIGUOUS, "save outcome unknown"));
        assertFalse(journal.playerStateSafeForRefund(record));
    }

    @Test
    void migratedLegacyMissingEffectsNeverBecomeNotAttemptedOrSafe() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-legacy-safety").toFile());
        for (MarketTransactionStatus status : new MarketTransactionStatus[]{
                MarketTransactionStatus.PREPARED,
                MarketTransactionStatus.ABORTED,
                MarketTransactionStatus.RECONCILIATION_REQUIRED}) {
            MarketTransactionRecord record = record();
            record.setStatus(status);
            assertNotNull(journal.migrateLegacyRecord(record));
            assertFalse(journal.isEffectNotAttempted(record,
                    MarketTransactionEffectType.COMPANY_SHARE_REMOVAL));
            assertFalse(journal.isEffectNotAttempted(record,
                    MarketTransactionEffectType.PLAYER_SHARE_ADD));
            assertFalse(journal.companyStateSafeForRefund(record));
            assertFalse(journal.playerStateSafeForRefund(record));
        }
    }

    @Test
    void legacyMarketImpactStartedIsAttemptedAndNotReplayable() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-impact-started").toFile());
        MarketTransactionRecord record = record();
        record.setStatus(MarketTransactionStatus.MARKET_IMPACT_STARTED);
        assertNotNull(journal.migrateLegacyRecord(record));
        assertEquals(MarketTransactionStatus.COMMITTED, record.getStatus());
        assertEquals(MarketTransactionEffectState.UNKNOWN,
                record.getEffects().stream()
                        .filter(effect -> effect.getType() == MarketTransactionEffectType.MARKET_IMPACT)
                        .findFirst().orElseThrow().getState());
        assertTrue(journal.hasMarketImpactAttempted(record));
        assertFalse(journal.isEffectNotAttempted(record,
                MarketTransactionEffectType.MARKET_IMPACT));
    }

    @Test
    void legacyMarketImpactAppliedIsConfirmed() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-impact-applied").toFile());
        MarketTransactionRecord record = record();
        record.setStatus(MarketTransactionStatus.MARKET_IMPACT_APPLIED);
        assertNotNull(journal.migrateLegacyRecord(record));
        assertEquals(MarketTransactionEffectState.CONFIRMED,
                record.getEffects().stream()
                        .filter(effect -> effect.getType() == MarketTransactionEffectType.MARKET_IMPACT)
                        .findFirst().orElseThrow().getState());
    }

    @Test
    void newSchemaAbsenceDerivesNotAttempted() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-not-attempted").toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.isEffectNotAttempted(record,
                MarketTransactionEffectType.COMPANY_SHARE_REMOVAL));
        assertTrue(journal.isEffectNotAttempted(record,
                MarketTransactionEffectType.PLAYER_SHARE_ADD));
    }

    @Test
    void outcomeSaveFailureFailsClosedAndLeavesRequestedDurable() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-save-failure").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root, attempt -> attempt != 7);
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        for (MarketTransactionEffectType type : new MarketTransactionEffectType[]{
                MarketTransactionEffectType.ECONOMY_DEBIT,
                MarketTransactionEffectType.COMPANY_SHARE_REMOVAL,
                MarketTransactionEffectType.PLAYER_SHARE_ADD}) {
            assertTrue(journal.requestEffect(record, type,
                    type == MarketTransactionEffectType.ECONOMY_DEBIT ? 10
                            : type == MarketTransactionEffectType.COMPANY_SHARE_REMOVAL ? 20 : 30,
                    type == MarketTransactionEffectType.ECONOMY_DEBIT ? record.getValue() : 0.0, record.getQuantity()));
            MarketTransactionEffect effect = record.getEffects().get(record.getEffects().size() - 1);
            if (type != MarketTransactionEffectType.PLAYER_SHARE_ADD) {
                assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(),
                        MarketTransactionEffectState.CONFIRMED, null));
            } else {
                assertFalse(journal.recordEffectOutcome(record, effect.getEffectId(),
                        MarketTransactionEffectState.CONFIRMED, null));
                assertFalse(record.getStatus() == MarketTransactionStatus.COMMITTED);
                assertFalse(record.getStatus() == MarketTransactionStatus.COMPLETED);
                assertFalse(record.getStatus() == MarketTransactionStatus.COMPENSATED);
            }
        }
        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionEffectState.REQUESTED,
                loaded.getEffects().get(2).getState());
        assertFalse(new MarketTransactionJournal(root).derivesCommitted(loaded));
    }

    @Test
    void outcomeSaveFailurePersistsReconciliationAfterReload() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-reconcile-reload").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root, attempt -> attempt != 3);
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                10, record.getValue(), record.getQuantity()));
        MarketTransactionEffect effect = record.getEffects().get(0);
        assertFalse(journal.recordEffectOutcome(record, effect.getEffectId(),
                MarketTransactionEffectState.CONFIRMED, "provider confirmed"));
        assertEquals(MarketTransactionStatus.IN_PROGRESS, record.getStatus());
        assertEquals(MarketTransactionEffectState.REQUESTED, effect.getState());
        assertTrue(journal.markReconciliationRequired(record, "outcome persistence failed"));

        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, loaded.getStatus());
        assertEquals(MarketTransactionEffectState.REQUESTED, loaded.getEffects().get(0).getState());
        assertTrue(new MarketTransactionJournal(root).isBlocking(loaded));
        assertNotNull(loaded.getFailureReason());
        assertFalse(loaded.getStatus().isTerminal());
    }

    @Test
    void outcomeAndReconciliationPersistenceFailureLeavesCriticalState() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-reconcile-failure").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root, attempt -> attempt != 3 && attempt != 4);
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                10, record.getValue(), record.getQuantity()));
        MarketTransactionEffect effect = record.getEffects().get(0);
        assertFalse(journal.recordEffectOutcome(record, effect.getEffectId(),
                MarketTransactionEffectState.CONFIRMED, "provider confirmed"));
        assertEquals(MarketTransactionStatus.IN_PROGRESS, record.getStatus());
        assertFalse(journal.markReconciliationRequired(record, "reconciliation failed"));
        assertEquals(MarketTransactionEffectState.REQUESTED, effect.getState());
    }

    @Test
    void currentSchemaRejectsUnknownOutcome() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-unknown").toFile());
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                10, record.getValue(), record.getQuantity()));
        MarketTransactionEffect effect = record.getEffects().get(0);
        assertFalse(journal.recordEffectOutcome(record, effect.getEffectId(),
                MarketTransactionEffectState.UNKNOWN, "legacy only"));
        assertEquals(MarketTransactionEffectState.REQUESTED, effect.getState());
        assertEquals(MarketTransactionStatus.IN_PROGRESS, record.getStatus());
    }

    @Test
    void legacyRecordRoundTripsAsExplicitMigrationInput() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-roundtrip").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord source = record();
        source.setStatus(MarketTransactionStatus.PREPARED);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        File transactions = new File(root, "transactions");
        assertTrue(transactions.mkdirs());
        mapper.writeValue(new File(transactions, source.getTransactionId() + ".yml"), source);
        MarketTransactionRecord loaded = journal.getRecord(source.getTransactionId()).orElseThrow();
        assertFalse(loaded.isCurrentSchema());
        MarketTransactionRecord migrated = journal.migrateLegacyRecord(loaded);
        assertNotNull(migrated);
        assertTrue(migrated.isLegacyMigrated());
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, migrated.getStatus());
    }

    @Test
    void malformedRecordFailsClosed() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-malformed").toFile();
        File transactions = new File(root, "transactions");
        assertTrue(transactions.mkdirs());
        Files.writeString(new File(transactions, UUID.randomUUID() + ".yml").toPath(),
                "status: PREPARED\n");

        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        boolean failedClosed = false;
        try {
            journal.getUnresolvedRecords();
        } catch (IllegalStateException e) {
            failedClosed = true;
        }
        assertTrue(failedClosed);
    }

    @Test
    void sellSequenceAcceptsCorrectEffects() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-sell-seq").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));

        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0, record.getQuantity()));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0, record.getQuantity()));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, record.getValue(), record.getQuantity()));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 40, 0.0, record.getQuantity()));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, 50, 0.0, record.getQuantity()));
        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0, record.getQuantity()));
    }

    @Test
    void sellSequenceRejectsOutOfOrderEffects() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-sell-bad").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));

        // Wrong sequence number for PLAYER_SHARE_REMOVAL
        assertFalse(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 20, 0.0, record.getQuantity()));

        // ECONOMY_DEBIT belongs to BUY, should be rejected for SELL with its sequence 10
        assertFalse(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT, 10, record.getValue(), record.getQuantity()));
    }

    @Test
    void buyContinuesToWorkNormally() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-sell-buy-compat").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = record();
        assertNotNull(journal.prepareCurrentSchema(record));

        assertTrue(journal.requestEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT, 10, record.getValue(), record.getQuantity()));

        // SELL's PLAYER_SHARE_REMOVAL should be rejected for BUY
        assertFalse(journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0, record.getQuantity()));
    }

    @Test
    void sellForwardEffectsWithFinancialAmbiguityBlockManualRepair() throws Exception {
        for (MarketTransactionEffectType type : new MarketTransactionEffectType[]{
                MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                MarketTransactionEffectType.COMPANY_SHARE_ADD,
                MarketTransactionEffectType.ECONOMY_CREDIT}) {
            for (MarketTransactionEffectState state : new MarketTransactionEffectState[]{
                    MarketTransactionEffectState.REQUESTED,
                    MarketTransactionEffectState.AMBIGUOUS}) {
                MarketTransactionJournal journal = new MarketTransactionJournal(
                        Files.createTempDirectory("stonks-journal-sell-ambiguity").toFile());
                MarketTransactionRecord record = recordSell();
                assertNotNull(journal.prepareCurrentSchema(record));
                assertTrue(journal.requestEffect(record, type,
                        switch (type) {
                            case PLAYER_SHARE_REMOVAL -> 10;
                            case COMPANY_SHARE_ADD -> 20;
                            case ECONOMY_CREDIT -> 30;
                            default -> throw new AssertionError(type);
                        }, type == MarketTransactionEffectType.ECONOMY_CREDIT
                                ? record.getValue() : 0.0, record.getQuantity()));
                MarketTransactionEffect effect = record.getEffects().get(0);
                if (state != MarketTransactionEffectState.REQUESTED) {
                    assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(), state,
                            "provider outcome ambiguous"));
                }
                assertTrue(journal.markReconciliationRequired(record, "financial outcome uncertain"));
                assertFalse(journal.resolveAfterManualRepair(record,
                        "manual-repair-confirmed: external verification"));
                assertTrue(journal.acknowledge(record, "operator inspected effect"));
                assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, record.getStatus());
            }
        }
    }

    @Test
    void sellForwardEffectsWithoutFinancialAmbiguityRemainBlockedByManualRepairContainment() throws Exception {
        for (MarketTransactionEffectType type : new MarketTransactionEffectType[]{
                MarketTransactionEffectType.PLAYER_SHARE_REMOVAL,
                MarketTransactionEffectType.COMPANY_SHARE_ADD,
                MarketTransactionEffectType.ECONOMY_CREDIT}) {
            MarketTransactionJournal journal = new MarketTransactionJournal(
                    Files.createTempDirectory("stonks-journal-sell-settled").toFile());
            MarketTransactionRecord record = recordSell();
            assertNotNull(journal.prepareCurrentSchema(record));
            assertTrue(journal.requestEffect(record, type,
                    switch (type) {
                        case PLAYER_SHARE_REMOVAL -> 10;
                        case COMPANY_SHARE_ADD -> 20;
                        case ECONOMY_CREDIT -> 30;
                        default -> throw new AssertionError(type);
                    }, type == MarketTransactionEffectType.ECONOMY_CREDIT
                            ? record.getValue() : 0.0, record.getQuantity()));
            MarketTransactionEffect effect = record.getEffects().get(0);
            assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(),
                    MarketTransactionEffectState.CONFIRMED, "provider confirmed"));
            assertTrue(journal.markReconciliationRequired(record, "operator review requested"));
            assertFalse(journal.resolveAfterManualRepair(record,
                    "manual-repair-confirmed: external verification"));
            assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, record.getStatus());
            assertTrue(journal.getUnresolvedRecords().contains(record));
        }
    }

    @Test
    void sellLifecycleDerivesCommittedAndCompletedAcrossReload() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-sell-complete").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, record.getValue(),
                MarketTransactionEffectState.CONFIRMED);
        assertEquals(MarketTransactionStatus.COMMITTED, record.getStatus());
        effect(journal, record, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        assertTrue(journal.complete(record));
        assertEquals(MarketTransactionStatus.COMPLETED, record.getStatus());
        assertTrue(new MarketTransactionJournal(root).getUnresolvedRecords().isEmpty());
        assertTrue(new MarketTransactionJournal(root).getRecord(record.getTransactionId()).isEmpty());
    }

    @Test
    void staleStatusTransitionCannotRegressReconciliationState() throws Exception {
        File root = Files.createTempDirectory("stonks-stale-status").toFile();
        MarketTransactionJournal journalA = new MarketTransactionJournal(root);
        MarketTransactionRecord seed = recordSell();
        assertNotNull(journalA.prepareCurrentSchema(seed));
        MarketTransactionRecord recordA = journalA.getRecord(seed.getTransactionId()).orElseThrow();
        MarketTransactionRecord recordB = new MarketTransactionJournal(root)
                .getRecord(seed.getTransactionId()).orElseThrow();

        assertTrue(journalA.markReconciliationRequired(recordA, "provider unavailable"));
        assertFalse(new MarketTransactionJournal(root).markEconomyApplied(recordB));

        MarketTransactionRecord persisted = new MarketTransactionJournal(root)
                .getRecord(seed.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, persisted.getStatus());
        assertEquals("provider unavailable", persisted.getFailureReason());
        assertTrue(new MarketTransactionJournal(root).isBlocking(persisted));
    }

    @Test
    void staleCompensationCannotReplaceNewerCompensatedState() throws Exception {
        File root = Files.createTempDirectory("stonks-stale-compensation").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord seed = recordSell();
        assertNotNull(journal.prepareCurrentSchema(seed));
        effect(journal, seed, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, seed, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0,
                MarketTransactionEffectState.DEFINITE_REJECTED);
        effect(journal, seed, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, 50, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        MarketTransactionRecord recordA = journal.getRecord(seed.getTransactionId()).orElseThrow();
        MarketTransactionRecord recordB = new MarketTransactionJournal(root)
                .getRecord(seed.getTransactionId()).orElseThrow();

        assertTrue(journal.saveCompensated(recordA));
        assertFalse(new MarketTransactionJournal(root).saveCompensated(recordB));
        MarketTransactionRecord persisted = new MarketTransactionJournal(root)
                .getRecord(seed.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.COMPENSATED, persisted.getStatus());
        assertEquals(3, persisted.getEffects().size());
        assertTrue(new MarketTransactionJournal(root).getUnresolvedRecords().isEmpty());
    }

    @Test
    void terminalArchiveRejectsStaleMutationAndFailureRetainsPending() throws Exception {
        File root = Files.createTempDirectory("stonks-terminal-race").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord seed = recordSell();
        assertNotNull(journal.prepareCurrentSchema(seed));
        effect(journal, seed, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, seed, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, seed, MarketTransactionEffectType.ECONOMY_CREDIT, 30, seed.getValue(),
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, seed, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        MarketTransactionRecord stale = journal.getRecord(seed.getTransactionId()).orElseThrow();
        assertTrue(journal.complete(seed));
        assertFalse(new MarketTransactionJournal(root).markReconciliationRequired(stale,
                "stale transition"));
        assertTrue(new MarketTransactionJournal(root).getRecord(seed.getTransactionId()).isEmpty());
        assertTrue(new MarketTransactionJournal(root).getUnresolvedRecords().isEmpty());

        MarketTransactionRecord pendingSeed = recordSell();
        assertNotNull(journal.prepareCurrentSchema(pendingSeed));
        effect(journal, pendingSeed, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, pendingSeed, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, pendingSeed, MarketTransactionEffectType.ECONOMY_CREDIT, 30, pendingSeed.getValue(),
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, pendingSeed, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        assertTrue(journal.derivesCompleted(pendingSeed));
        MarketTransactionJournal failing = new MarketTransactionJournal(root, attempt -> false);
        assertFalse(failing.complete(pendingSeed));
        assertTrue(new MarketTransactionJournal(root)
                .getRecord(pendingSeed.getTransactionId()).isPresent());
    }

    @Test
    void existingArchiveBlocksWithoutReplacingOrDeletingPending() throws Exception {
        File root = Files.createTempDirectory("stonks-archive-collision").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.DEFINITE_REJECTED);
        record.setStatus(MarketTransactionStatus.ABORTED);
        record.setFailureReason("terminal collision");
        me.geraldr12.data.sources.yml.YmlDataSourceImpl<MarketTransactionRecord> source =
                new me.geraldr12.data.sources.yml.YmlDataSourceImpl<>(root);
        assertTrue(source.save("transactions/" + record.getTransactionId() + ".yml", record));

        File archive = new File(root, "transactions/archive");
        assertTrue(archive.isDirectory() || archive.mkdirs());
        String archivePath = "transactions/archive/" + record.getTransactionId() + ".yml";
        MarketTransactionRecord existing = recordSell();
        existing.setTransactionId(record.getTransactionId());
        existing.setStateRevision(record.getStateRevision() + 1);
        assertTrue(source.save(archivePath, existing));
        assertTrue(new MarketTransactionJournal(root).getRecord(record.getTransactionId()).isPresent());
        assertTrue(new File(root, archivePath).isFile());

        assertFalse(journal.recoverTerminalPendingRecords());
        assertTrue(new MarketTransactionJournal(root).getRecord(record.getTransactionId()).isPresent());
        assertEquals(existing, source.load(archivePath, MarketTransactionRecord.class));
    }

    @Test
    void equivalentArchiveAlsoBlocksWithoutDeletingPending() throws Exception {
        File root = Files.createTempDirectory("stonks-equivalent-archive-collision").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.DEFINITE_REJECTED);
        record.setStatus(MarketTransactionStatus.ABORTED);
        record.setFailureReason("terminal equivalent collision");
        me.geraldr12.data.sources.yml.YmlDataSourceImpl<MarketTransactionRecord> source =
                new me.geraldr12.data.sources.yml.YmlDataSourceImpl<>(root);
        assertTrue(source.save("transactions/" + record.getTransactionId() + ".yml", record));
        assertTrue(source.save("transactions/archive/" + record.getTransactionId() + ".yml", record));

        assertTrue(journal.recoverTerminalPendingRecords());
        assertFalse(new File(root, "transactions/" + record.getTransactionId() + ".yml").exists());
        assertEquals(record, source.load("transactions/archive/" + record.getTransactionId() + ".yml",
                MarketTransactionRecord.class));
        assertTrue(journal.recoverTerminalPendingRecords());
    }

    @Test
    void deleteFailurePreservesPendingAndCreatedArchive() throws Exception {
        File root = Files.createTempDirectory("stonks-archive-delete-failure").toFile();
        MarketTransactionJournal seedJournal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(seedJournal.prepareCurrentSchema(record));
        effect(seedJournal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.DEFINITE_REJECTED);
        record.setStatus(MarketTransactionStatus.ABORTED);
        record.setFailureReason("terminal delete failure");
        me.geraldr12.data.sources.yml.YmlDataSourceImpl<MarketTransactionRecord> seedSource =
                new me.geraldr12.data.sources.yml.YmlDataSourceImpl<>(root);
        assertTrue(seedSource.save("transactions/" + record.getTransactionId() + ".yml", record));

        MarketTransactionJournal failing = new MarketTransactionJournal(root,
                ignored -> true, ignored -> false);
        assertFalse(failing.recoverTerminalPendingRecords());
        assertTrue(new MarketTransactionJournal(root).getRecord(record.getTransactionId()).isPresent());
        me.geraldr12.data.sources.yml.YmlDataSourceImpl<MarketTransactionRecord> source =
                new me.geraldr12.data.sources.yml.YmlDataSourceImpl<>(root);
        assertNotNull(source.load("transactions/archive/" + record.getTransactionId() + ".yml",
                MarketTransactionRecord.class));
    }

    @Test
    void sellPlayerRemovalRejectionDerivesAborted() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-sell-abort").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.DEFINITE_REJECTED);
        assertTrue(journal.abort(record, "player shares were not removed"));
        assertEquals(MarketTransactionStatus.ABORTED, record.getStatus());
        assertTrue(new MarketTransactionJournal(root).getUnresolvedRecords().isEmpty());
    }

    @Test
    void sellCompanyRejectionWithPlayerRestorationDerivesCompensated() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-sell-comp-add").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0,
                MarketTransactionEffectState.DEFINITE_REJECTED);
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, 50, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        assertTrue(journal.saveCompensated(record));
        assertEquals(MarketTransactionStatus.COMPENSATED, record.getStatus());

        MarketTransactionJournal reloaded = new MarketTransactionJournal(root);
        MarketTransactionRecord persisted = reloaded.getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.COMPENSATED, persisted.getStatus());
        assertTrue(reloaded.getUnresolvedRecords().isEmpty());
    }

    @Test
    void sellCreditRejectionWithBothCompensationsDerivesCompensated() throws Exception {
        File root = Files.createTempDirectory("stonks-journal-sell-comp-credit").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, record.getValue(),
                MarketTransactionEffectState.DEFINITE_REJECTED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 40, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, 50, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        assertTrue(journal.saveCompensated(record));
        assertEquals(MarketTransactionStatus.COMPENSATED, record.getStatus());

        MarketTransactionJournal reloaded = new MarketTransactionJournal(root);
        MarketTransactionRecord persisted = reloaded.getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(MarketTransactionStatus.COMPENSATED, persisted.getStatus());
        assertTrue(reloaded.getUnresolvedRecords().isEmpty());
    }

    @Test
    void sellAmbiguousCreditCannotDeriveCompensated() throws Exception {
        MarketTransactionJournal journal = new MarketTransactionJournal(
                Files.createTempDirectory("stonks-journal-sell-comp-ambiguous").toFile());
        MarketTransactionRecord record = recordSell();
        assertNotNull(journal.prepareCurrentSchema(record));
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, record.getValue(),
                MarketTransactionEffectState.AMBIGUOUS);
        effect(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 40, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        effect(journal, record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, 50, 0.0,
                MarketTransactionEffectState.CONFIRMED);
        assertFalse(journal.saveCompensated(record));
        assertEquals(MarketTransactionStatus.IN_PROGRESS, record.getStatus());
    }

    private void effect(MarketTransactionJournal journal, MarketTransactionRecord record,
                        MarketTransactionEffectType type, int sequence, double amount,
                        MarketTransactionEffectState state) {
        assertTrue(journal.requestEffect(record, type, sequence, amount, record.getQuantity()));
        MarketTransactionEffect effect = record.getEffects().stream()
                .filter(candidate -> candidate.getType() == type).findFirst().orElseThrow();
        assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(), state, "test outcome"));
    }

    private MarketTransactionRecord recordSell() {
        MarketTransactionRecord record = record();
        record.setOperation("SELL");
        return record;
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
