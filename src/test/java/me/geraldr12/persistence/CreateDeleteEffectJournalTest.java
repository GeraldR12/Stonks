package me.geraldr12.persistence;

import me.geraldr12.data.sources.yml.YmlDataSourceImpl;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CreateDeleteEffectJournalTest {
    @TempDir Path temp;

    private List<MarketTransactionEffectType> types(String operation) {
        return (operation.equals("CREATE")
                ? Stream.of("ECONOMY_DEBIT", "COMPANY_RECORD_CREATE", "COMPANY_SHARE_REMOVAL", "PLAYER_SHARE_ADD")
                : Stream.of("ECONOMY_CREDIT", "PLAYER_SHARE_REMOVAL", "COMPANY_RECORD_DELETE"))
                .map(MarketTransactionEffectType::valueOf).toList();
    }

    private MarketTransactionRecord record(String operation) {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(UUID.randomUUID().toString());
        record.setOperation(operation);
        record.setPlayerUuid(UUID.randomUUID().toString());
        record.setCompanyId(7L);
        record.setQuantity(10);
        record.setValue(operation.equals("CREATE") ? 22.0 : 20.0);
        record.setTimestamp(System.currentTimeMillis());
        record.setQuoteCompanyId(record.getCompanyId());
        record.setQuotePlayerUuid(record.getPlayerUuid());
        record.setQuoteQuantity(record.getQuantity());
        record.setQuoteUnitSharePrice(2.0);
        record.setQuoteTotalValue(record.getValue());
        record.setQuoteTimestamp(record.getTimestamp());
        return record;
    }

    @TestFactory
    Stream<DynamicTest> lifecycle() {
        return Stream.of("CREATE", "DELETE").map(operation -> DynamicTest.dynamicTest(operation, () -> {
            MarketTransactionJournal journal = new MarketTransactionJournal(temp.resolve(operation).toFile());
            MarketTransactionRecord record = record(operation);
            assertNotNull(journal.prepareCurrentSchema(record));
            List<MarketTransactionEffectType> types = types(operation);
            for (int i = 0; i < types.size(); i++) {
                MarketTransactionEffectType type = types.get(i);
                assertFalse(journal.derivesCommitted(record));
                assertFalse(journal.derivesCompleted(record));
                assertFalse(journal.complete(record));
                assertTrue(journal.requestEffect(record, type, (i + 1) * 10,
                        i == 0 ? record.getValue() : 0.0, 10));
                assertFalse(journal.derivesCommitted(record));
                MarketTransactionEffect effect = record.getEffects().get(i);
                assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(),
                        MarketTransactionEffectState.CONFIRMED, "confirmed"));
                assertFalse(journal.derivesCompensated(record));
                assertFalse(journal.saveCompensated(record));
            }
            assertEquals(MarketTransactionStatus.COMMITTED, record.getStatus());
            assertTrue(journal.derivesCommitted(record));
            assertTrue(journal.derivesCompleted(record));
            assertFalse(journal.requestEffect(record, MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0, 10));
            assertTrue(journal.complete(record));
            assertEquals(MarketTransactionStatus.COMPLETED, record.getStatus());
            assertEquals(JournalIntegrityAssessment.VALID, journal.inspectIntegrity().getAssessment());
            assertTrue(journal.getUnresolvedRecords().isEmpty());
        }));
    }

    @TestFactory
    Stream<DynamicTest> sequencesAndPayloads() {
        return Stream.of("CREATE", "DELETE").flatMap(operation -> Stream.of(MarketTransactionEffectType.values())
                .map(type -> DynamicTest.dynamicTest(operation + "/" + type, () -> {
                    MarketTransactionJournal journal = new MarketTransactionJournal(
                            temp.resolve(operation + type).toFile());
                    MarketTransactionRecord record = record(operation);
                    assertNotNull(journal.prepareCurrentSchema(record));
                    int index = types(operation).indexOf(type);
                    boolean monetary = type.name().startsWith("ECONOMY_");
                    double amount = monetary ? record.getValue() : 0.0;
                    for (int sequence : new int[]{-1, 0, 10, 20, 30, 40, 50, 60, 70}) {
                        if (index >= 0 && sequence == (index + 1) * 10) continue;
                        assertFalse(journal.requestEffect(record, type, sequence, amount, 10), "sequence=" + sequence);
                    }
                    if (index < 0) return;
                    int sequence = (index + 1) * 10;
                    assertFalse(journal.requestEffect(record, type, sequence, amount, 9));
                    assertFalse(journal.requestEffect(record, type, sequence, amount, 0));
                    for (double invalid : new double[]{-0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1.0}) {
                        if (!monetary && Double.doubleToRawLongBits(invalid) == 0L) continue;
                        assertFalse(journal.requestEffect(record, type, sequence, invalid, 10));
                    }
                    assertTrue(journal.requestEffect(record, type, sequence, amount, 10));
                    assertFalse(journal.requestEffect(record, type, sequence, amount, 10));
                })));
    }

    @TestFactory
    Stream<DynamicTest> invalidQuotes() {
        List<Consumer<MarketTransactionRecord>> invalid = List.of(
                r -> r.setQuoteCompanyId(null), r -> r.setQuoteCompanyId(8L),
                r -> r.setQuotePlayerUuid(null), r -> r.setQuotePlayerUuid(UUID.randomUUID().toString()),
                r -> r.setQuoteQuantity(null), r -> r.setQuoteQuantity(9), r -> r.setQuoteQuantity(0),
                r -> r.setQuantity(0), r -> r.setQuoteUnitSharePrice(null),
                r -> r.setQuoteUnitSharePrice(0.0), r -> r.setQuoteUnitSharePrice(Double.NaN),
                r -> r.setQuoteUnitSharePrice(Double.POSITIVE_INFINITY), r -> r.setQuoteTotalValue(null),
                r -> r.setQuoteTotalValue(0.0), r -> r.setQuoteTotalValue(1.0),
                r -> r.setQuoteTotalValue(Double.NaN), r -> r.setQuoteTotalValue(Double.POSITIVE_INFINITY),
                r -> r.setQuoteTimestamp(null), r -> r.setQuoteTimestamp(0L));
        return Stream.of("CREATE", "DELETE").flatMap(operation -> IntStream.range(0, invalid.size())
                .mapToObj(i -> DynamicTest.dynamicTest(operation + "/quote-" + i, () -> {
                    MarketTransactionJournal journal = new MarketTransactionJournal(temp.resolve(operation + i).toFile());
                    MarketTransactionRecord record = record(operation);
                    invalid.get(i).accept(record);
                    assertNull(journal.prepareCurrentSchema(record));
                })));
    }

    @TestFactory
    Stream<DynamicTest> abortRequiresProvenMonetaryRejection() {
        return Stream.of("CREATE", "DELETE").flatMap(operation -> Stream.of(
                MarketTransactionEffectState.REQUESTED, MarketTransactionEffectState.AMBIGUOUS,
                MarketTransactionEffectState.CONFIRMED, MarketTransactionEffectState.DEFINITE_REJECTED)
                .map(state -> DynamicTest.dynamicTest(operation + "/" + state, () -> {
                    MarketTransactionJournal journal = new MarketTransactionJournal(temp.resolve(operation + state).toFile());
                    MarketTransactionRecord record = record(operation);
                    assertNotNull(journal.prepareCurrentSchema(record));
                    assertFalse(journal.derivesAborted(record));
                    assertTrue(journal.requestEffect(record, types(operation).get(0), 10, record.getValue(), 10));
                    if (state != MarketTransactionEffectState.REQUESTED) {
                        assertTrue(journal.recordEffectOutcome(record, record.getEffects().get(0).getEffectId(), state, "outcome"));
                    }
                    assertEquals(state == MarketTransactionEffectState.DEFINITE_REJECTED, journal.derivesAborted(record));
                    assertEquals(state == MarketTransactionEffectState.DEFINITE_REJECTED, journal.abort(record, "rejected"));
                    assertFalse(journal.derivesCompensated(record));
                })));
    }

    @TestFactory
    Stream<DynamicTest> legacyMigrationEveryStatus() {
        return Stream.of("CREATE", "DELETE").flatMap(operation -> Stream.of(
                MarketTransactionStatus.PREPARED, MarketTransactionStatus.ABORTED,
                MarketTransactionStatus.RECONCILIATION_REQUIRED, MarketTransactionStatus.ECONOMY_APPLIED,
                MarketTransactionStatus.STATE_COMMITTED, MarketTransactionStatus.MARKET_IMPACT_STARTED,
                MarketTransactionStatus.MARKET_IMPACT_APPLIED, MarketTransactionStatus.COMPLETED)
                .map(status -> DynamicTest.dynamicTest(operation + "/legacy-" + status, () -> {
                    Path root = temp.resolve(operation + status);
                    MarketTransactionJournal journal = new MarketTransactionJournal(root.toFile());
                    MarketTransactionRecord record = record(operation);
                    record.setSchemaVersion(2);
                    record.setStatus(status);
                    record.setQuoteCompanyId(null);
                    record.setQuotePlayerUuid(null);
                    record.setQuoteQuantity(null);
                    record.setQuoteUnitSharePrice(null);
                    record.setQuoteTotalValue(null);
                    record.setQuoteTimestamp(null);
                    assertNotNull(journal.migrateLegacyRecord(record));
                    boolean committed = status == MarketTransactionStatus.STATE_COMMITTED
                            || status == MarketTransactionStatus.MARKET_IMPACT_STARTED
                            || status == MarketTransactionStatus.MARKET_IMPACT_APPLIED
                            || status == MarketTransactionStatus.COMPLETED;
                    assertEquals(status == MarketTransactionStatus.COMPLETED ? MarketTransactionStatus.COMPLETED
                            : committed ? MarketTransactionStatus.COMMITTED
                            : MarketTransactionStatus.RECONCILIATION_REQUIRED, record.getStatus());
                    assertEquals(types(operation), record.getEffects().stream().map(MarketTransactionEffect::getType).toList());
                    for (int i = 0; i < record.getEffects().size(); i++) {
                        assertEquals(i, record.getEffects().get(i).getSequence());
                        assertEquals(committed || (i == 0 && status == MarketTransactionStatus.ECONOMY_APPLIED)
                                ? MarketTransactionEffectState.CONFIRMED : MarketTransactionEffectState.UNKNOWN,
                                record.getEffects().get(i).getState());
                    }
                    assertTrue(journal.bootstrapDirectories());
                    assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(root.toFile())
                            .save("transactions/" + record.getTransactionId() + ".yml", record));
                    assertEquals(record, journal.getRecord(record.getTransactionId()).orElseThrow());
                    assertEquals(JournalIntegrityAssessment.VALID, journal.inspectIntegrity().getAssessment());
                    assertEquals(committed, journal.derivesCommitted(record));
                    assertFalse(journal.derivesCompensated(record));
                    if (!committed) {
                        assertTrue(journal.acknowledge(record, "operator inspected"));
                        assertTrue(journal.isBlocking(record));
                        assertEquals(1, journal.getUnresolvedRecords().size());
                    }
                })));
    }

    @TestFactory
    Stream<DynamicTest> literalLegacyYamlLoadsInPendingAndArchive() {
        return Stream.of("CREATE", "DELETE").flatMap(operation -> Stream.of(false, true)
                .map(archive -> DynamicTest.dynamicTest(operation + "/archive=" + archive, () -> {
                    Path root = temp.resolve(operation + archive);
                    MarketTransactionJournal journal = new MarketTransactionJournal(root.toFile());
                    assertTrue(journal.bootstrapDirectories());
                    String id = UUID.randomUUID().toString();
                    String yaml = """
                            schemaVersion: 2
                            transactionId: "%s"
                            operation: "%s"
                            playerUuid: "764bd0df-c04e-43e8-b6b8-61b61077a128"
                            companyId: 7
                            quantity: 10
                            value: 20.0
                            timestamp: 1795000000000
                            status: %s
                            """.formatted(id, operation, archive ? "COMPLETED" : "ECONOMY_APPLIED");
                    Path path = root.resolve("transactions/" + (archive ? "archive/" : "") + id + ".yml");
                    Files.writeString(path, yaml);
                    assertEquals(JournalIntegrityAssessment.VALID, journal.inspectIntegrity().getAssessment());
                    MarketTransactionRecord loaded = archive
                            ? new YmlDataSourceImpl<MarketTransactionRecord>(root.toFile()).load(
                                    "transactions/archive/" + id + ".yml", MarketTransactionRecord.class)
                            : journal.getRecord(id).orElseThrow();
                    assertNotNull(journal.migrateLegacyRecord(loaded));
                    assertEquals(types(operation), loaded.getEffects().stream().map(MarketTransactionEffect::getType).toList());
                })));
    }

    @TestFactory
    Stream<DynamicTest> forgedTerminalAndForeignEffectsFailIntegrity() {
        return Stream.of("CREATE", "DELETE").flatMap(operation -> Stream.of("committed", "completed", "compensated", "aborted", "foreign")
                .map(tamper -> DynamicTest.dynamicTest(operation + "/tamper-" + tamper, () -> {
                    Path root = temp.resolve(operation + tamper);
                    MarketTransactionJournal journal = new MarketTransactionJournal(root.toFile());
                    MarketTransactionRecord record = record(operation);
                    assertNotNull(journal.prepareCurrentSchema(record));
                    assertTrue(journal.requestEffect(record, types(operation).get(0), 10, record.getValue(), 10));
                    record.setStatus(switch (tamper) {
                        case "committed" -> MarketTransactionStatus.COMMITTED;
                        case "completed" -> MarketTransactionStatus.COMPLETED;
                        case "compensated" -> MarketTransactionStatus.COMPENSATED;
                        case "aborted" -> MarketTransactionStatus.ABORTED;
                        default -> MarketTransactionStatus.IN_PROGRESS;
                    });
                    if (tamper.equals("foreign")) record.getEffects().add(MarketTransactionEffect.requested(
                            record.getTransactionId(), MarketTransactionEffectType.MARKET_IMPACT, 70, 0.0, 10));
                    assertFalse(journal.derivesCompleted(record));
                    assertFalse(journal.derivesCompensated(record));
                    assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(root.toFile())
                            .save("transactions/" + record.getTransactionId() + ".yml", record));
                    assertThrows(IllegalStateException.class, () -> journal.getRecord(record.getTransactionId()));
                    assertEquals(JournalIntegrityAssessment.INVALID, journal.inspectIntegrity().getAssessment());
                })));
    }

    @Test
    void unknownOperationNeverUsesBuySemantics() {
        MarketTransactionJournal journal = new MarketTransactionJournal(temp.toFile());
        MarketTransactionRecord record = record("UNKNOWN");
        assertNull(journal.prepareCurrentSchema(record));
        record.setSchemaVersion(2);
        assertNotNull(journal.prepare(record));
        record.setSchemaVersion(3);
        new YmlDataSourceImpl<MarketTransactionRecord>(temp.toFile())
                .save("transactions/" + record.getTransactionId() + ".yml", record);
        assertThrows(IllegalStateException.class, () -> journal.getRecord(record.getTransactionId()));
    }
}
