package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.persistence.MarketTransactionEffect;
import me.geraldr12.persistence.MarketTransactionEffectState;
import me.geraldr12.persistence.MarketTransactionEffectType;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.persistence.MarketTransactionStatus;
import me.geraldr12.utils.MarketTransactionLocks;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SellTransactionRestartIsolationTest {

    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long COMPANY_X = 101L;
    private static final long COMPANY_Y = 202L;

    @TempDir
    Path tempDir;

    @Test
    void preparedSellReloadsWithoutExecutingAnything() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal);

        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());

        assertEquals(MarketTransactionStatus.PREPARED, recordFrom(reopened, record).getStatus());
        assertEquals(1, reopened.getUnresolvedRecords().size());
        assertTrue(recordFrom(reopened, record).getEffects().isEmpty());
    }

    @Test
    void requestedForwardEffectsRemainRequestedAfterRestart() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal);
        request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.REQUESTED);
        request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, MarketTransactionEffectState.REQUESTED);
        request(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, MarketTransactionEffectState.REQUESTED);

        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord recovered = recordFrom(reopened, record);

        assertEquals(MarketTransactionStatus.IN_PROGRESS, recovered.getStatus());
        assertEquals(MarketTransactionEffectState.REQUESTED, state(recovered, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL));
        assertEquals(MarketTransactionEffectState.REQUESTED, state(recovered, MarketTransactionEffectType.COMPANY_SHARE_ADD));
        assertEquals(MarketTransactionEffectState.REQUESTED, state(recovered, MarketTransactionEffectType.ECONOMY_CREDIT));
        assertEquals(1, reopened.getUnresolvedRecords().size());
    }

    @Test
    void requestedCreditWithConfirmedFinancialPrefixIsBlockedAfterRestart() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal, PLAYER_A, COMPANY_X);
        request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, MarketTransactionEffectState.REQUESTED);

        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord recovered = recordFrom(reopened, record);
        assertEquals("SELL", recovered.getOperation());
        assertEquals(record.getTransactionId(), recovered.getTransactionId());
        assertEquals(MarketTransactionStatus.IN_PROGRESS, recovered.getStatus());
        assertEquals(MarketTransactionEffectState.CONFIRMED, state(recovered, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL));
        assertEquals(MarketTransactionEffectState.CONFIRMED, state(recovered, MarketTransactionEffectType.COMPANY_SHARE_ADD));
        assertEquals(MarketTransactionEffectState.REQUESTED, state(recovered, MarketTransactionEffectType.ECONOMY_CREDIT));
        assertEquals(1, reopened.getUnresolvedRecords().size());

        assertBlockedSellWithoutMutations(reopened, recovered);
    }

    @Test
    void confirmedPrefixDoesNotReplayCompanyAdd() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal);
        request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, MarketTransactionEffectState.REQUESTED);

        MarketTransactionRecord reopened = recordFrom(new MarketTransactionJournal(tempDir.toFile()), record);

        assertEquals(MarketTransactionEffectState.CONFIRMED, state(reopened, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL));
        assertEquals(MarketTransactionEffectState.REQUESTED, state(reopened, MarketTransactionEffectType.COMPANY_SHARE_ADD));
        assertEquals(1, new MarketTransactionJournal(tempDir.toFile()).getUnresolvedRecords().size());
    }

    @Test
    void ambiguousCreditRemainsBlockedAndIsNeverReversed() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal, PLAYER_A, COMPANY_X);
        request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, MarketTransactionEffectState.AMBIGUOUS);

        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord recovered = recordFrom(reopened, record);

        assertEquals(MarketTransactionEffectState.AMBIGUOUS, state(recovered, MarketTransactionEffectType.ECONOMY_CREDIT));
        assertEquals(MarketTransactionStatus.IN_PROGRESS, recovered.getStatus());
        assertEquals(1, reopened.getUnresolvedRecords().size());
        assertBlockedSellWithoutMutations(reopened, recovered);
    }

    @Test
    void compensationRequestsNeverReplayAfterRestart() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal);
        request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, MarketTransactionEffectState.DEFINITE_REJECTED);
        request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL, 40, MarketTransactionEffectState.REQUESTED);
        request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, 50, MarketTransactionEffectState.REQUESTED);

        MarketTransactionRecord reopened = recordFrom(new MarketTransactionJournal(tempDir.toFile()), record);

        assertEquals(MarketTransactionEffectState.REQUESTED, state(reopened, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL));
        assertEquals(MarketTransactionEffectState.REQUESTED, state(reopened, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION));
        assertEquals(1, new MarketTransactionJournal(tempDir.toFile()).getUnresolvedRecords().size());
    }

    @Test
    void confirmedFinancialSellWithPendingMarketImpactSurvivesRestart() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal);
        request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, MarketTransactionEffectState.CONFIRMED);
        assertEquals(MarketTransactionStatus.COMMITTED, record.getStatus());
        request(journal, record, MarketTransactionEffectType.MARKET_IMPACT, 70, MarketTransactionEffectState.REQUESTED);

        MarketTransactionRecord reopened = recordFrom(new MarketTransactionJournal(tempDir.toFile()), record);

        assertEquals(MarketTransactionStatus.COMMITTED, reopened.getStatus());
        assertEquals(MarketTransactionEffectState.CONFIRMED, state(reopened, MarketTransactionEffectType.ECONOMY_CREDIT));
        assertEquals(MarketTransactionEffectState.REQUESTED, state(reopened, MarketTransactionEffectType.MARKET_IMPACT));
        assertEquals(1, new MarketTransactionJournal(tempDir.toFile()).getUnresolvedRecords().size());
    }

    @Test
    void completedSellIsArchivedAndDoesNotReactivate() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal);
        request(journal, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.ECONOMY_CREDIT, 30, MarketTransactionEffectState.CONFIRMED);
        request(journal, record, MarketTransactionEffectType.MARKET_IMPACT, 70, MarketTransactionEffectState.CONFIRMED);

        assertTrue(journal.complete(record));
        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());

        assertTrue(reopened.getAllPendingOrReconciliationRecords().isEmpty());
        assertTrue(reopened.getUnresolvedRecords().isEmpty());
        assertTrue(archiveFile(record).isFile());
    }

    @Test
    void abortedAndCompensatedSellsRemainTerminalAfterRestart() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord aborted = prepare(journal);
        request(journal, aborted, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.DEFINITE_REJECTED);
        assertTrue(journal.abort(aborted, "player removal rejected"));
        assertTrue(archiveFile(aborted).isFile());

        MarketTransactionRecord compensated = prepare(journal);
        request(journal, compensated, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.CONFIRMED);
        request(journal, compensated, MarketTransactionEffectType.COMPANY_SHARE_ADD, 20, MarketTransactionEffectState.DEFINITE_REJECTED);
        request(journal, compensated, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION, 50, MarketTransactionEffectState.CONFIRMED);
        assertTrue(journal.saveCompensated(compensated));

        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());
        assertTrue(reopened.getUnresolvedRecords().isEmpty());
        assertEquals(MarketTransactionStatus.COMPENSATED, recordFrom(reopened, compensated).getStatus());
    }

    @Test
    void lockIdentityIsolationUsesBothPlayerAndCompany() {
        MarketTransactionJournal journal = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(journal, PLAYER_A, COMPANY_X);
        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());
        LockState previousLocks = snapshotLocks();
        try {
            MarketTransactionLocks.initialize(reopened);
            assertTrue(MarketTransactionLocks.requiresReconciliation(PLAYER_A, COMPANY_X));
            assertTrue(MarketTransactionLocks.requiresReconciliation(PLAYER_A, COMPANY_Y));
            assertTrue(MarketTransactionLocks.requiresReconciliation(PLAYER_B, COMPANY_X));
            assertFalse(MarketTransactionLocks.requiresReconciliation(PLAYER_B, COMPANY_Y));
        } finally {
            restoreLocks(previousLocks);
        }
    }

    @Test
    void realSellIsBlockedAfterRestartWithoutPreparingOrMutating() {
        MarketTransactionJournal original = new MarketTransactionJournal(tempDir.toFile());
        MarketTransactionRecord record = prepare(original, PLAYER_A, COMPANY_X);
        request(original, record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, MarketTransactionEffectState.REQUESTED);
        MarketTransactionJournal reopened = new MarketTransactionJournal(tempDir.toFile());

        assertBlockedSellWithoutMutations(reopened, recordFrom(reopened, record));
    }

    private void assertBlockedSellWithoutMutations(MarketTransactionJournal reopened, MarketTransactionRecord record) {
        MarketTransactionJournal journalSpy = spy(reopened);
        Stonks plugin = mock(Stonks.class);
        SellTransactionService.EconomyMutator economy = mock(SellTransactionService.EconomyMutator.class);
        CompaniesService companies = mock(CompaniesService.class);
        PlayersService players = mock(PlayersService.class);
        when(plugin.getTransactionJournal()).thenReturn(journalSpy);
        when(plugin.isJournalReady()).thenReturn(true);
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getPlayersService()).thenReturn(players);
        SellTransactionService service = new SellTransactionService(plugin, economy);

        LockState previousLocks = snapshotLocks();
        try {
            MarketTransactionLocks.initialize(journalSpy);
            assertTrue(MarketTransactionLocks.activate(plugin, journalSpy));
            assertTrue(MarketTransactionLocks.requiresReconciliation(player(record), company(record)));
            SellTransactionResult result = service.execute(player(record), mock(OfflinePlayer.class), company(record), record.getQuantity());

            assertEquals(SellTransactionResult.Status.BLOCKED_PENDING_REVIEW, result.status());
            assertEquals(1, journalSpy.getAllPendingOrReconciliationRecords().size());
            verifyNoInteractions(companies, players, economy);
            verify(journalSpy, never()).prepareCurrentSchema(any(MarketTransactionRecord.class));
        } finally {
            restoreLocks(previousLocks);
        }
    }

    private MarketTransactionRecord prepare(MarketTransactionJournal journal) {
        return prepare(journal, UUID.randomUUID(), 303L);
    }

    private MarketTransactionRecord prepare(MarketTransactionJournal journal, UUID player, long company) {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(UUID.randomUUID().toString());
        record.setOperation("SELL");
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

    private MarketTransactionRecord recordFrom(MarketTransactionJournal journal, MarketTransactionRecord record) {
        return journal.getRecord(record.getTransactionId()).orElseThrow();
    }

    private void request(MarketTransactionJournal journal, MarketTransactionRecord record,
                         MarketTransactionEffectType type, int sequence, MarketTransactionEffectState outcome) {
        double amount = type == MarketTransactionEffectType.ECONOMY_CREDIT ? record.getValue() : 0.0;
        assertTrue(journal.requestEffect(record, type, sequence, amount, record.getQuantity()));
        if (outcome != MarketTransactionEffectState.REQUESTED) {
            MarketTransactionEffect effect = record.getEffects().stream()
                    .filter(candidate -> candidate.getType() == type).findFirst().orElseThrow();
            assertTrue(journal.recordEffectOutcome(record, effect.getEffectId(), outcome, "test outcome"));
        }
    }

    private MarketTransactionEffectState state(MarketTransactionRecord record, MarketTransactionEffectType type) {
        return record.getEffects().stream().filter(effect -> effect.getType() == type)
                .map(MarketTransactionEffect::getState).findFirst().orElse(null);
    }

    private UUID player(MarketTransactionRecord record) {
        return UUID.fromString(record.getPlayerUuid());
    }

    private long company(MarketTransactionRecord record) {
        return record.getCompanyId();
    }

    private File archiveFile(MarketTransactionRecord record) {
        return new File(tempDir.toFile(), "transactions/archive/" + record.getTransactionId() + ".yml");
    }

    private LockState snapshotLocks() {
        try {
            Field journalField = MarketTransactionLocks.class.getDeclaredField("journal");
            Field initializedField = MarketTransactionLocks.class.getDeclaredField("initialized");
            journalField.setAccessible(true);
            initializedField.setAccessible(true);
            return new LockState((MarketTransactionJournal) journalField.get(null), initializedField.getBoolean(null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void restoreLocks(LockState previous) {
        try {
            Field journalField = MarketTransactionLocks.class.getDeclaredField("journal");
            Field initializedField = MarketTransactionLocks.class.getDeclaredField("initialized");
            journalField.setAccessible(true);
            initializedField.setAccessible(true);
            journalField.set(null, previous.journal());
            initializedField.setBoolean(null, previous.initialized());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record LockState(MarketTransactionJournal journal, boolean initialized) {
    }
}
