package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.persistence.MarketTransactionEffect;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionEffectState;
import me.geraldr12.persistence.MarketTransactionEffectType;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.persistence.MarketTransactionStatus;
import me.geraldr12.persistence.ReconciliationEvidence;
import me.geraldr12.persistence.ReconciliationEvidenceType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReconcileCommandInvestigationTest {

    private static final String TRANSACTION_ID = "11111111-1111-1111-1111-111111111111";
    private static final String EFFECT_ID = "22222222-2222-2222-2222-222222222222";
    private static final UUID OPERATOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void consoleCannotUseInvestigationNamespace() {
        Stonks plugin = mock(Stonks.class);
        CommandSender console = mock(CommandSender.class);
        ReconcileCommand command = new ReconcileCommand(plugin);

        command.execute(console, new String[]{"investigate", "evidence"});

        verify(console).sendMessage("Investigation commands require an authenticated player.");
        verifyNoInteractions(plugin);
    }

    @Test
    void investigatorPermissionDoesNotGrantLegacyResolve() {
        Stonks plugin = mock(Stonks.class);
        Player player = mock(Player.class);
        when(player.hasPermission("stonks.admin.reconcile")).thenReturn(false);
        when(player.hasPermission("stonks.admin.investigate")).thenReturn(true);
        ReconcileCommand command = new ReconcileCommand(plugin);

        command.execute(player, new String[]{"resolve", TRANSACTION_ID, "acknowledge:review"});

        verify(player).sendMessage("You do not have permission to reconcile transactions.");
        verifyNoInteractions(plugin);
    }

    @Test
    void evidenceUsesAuthenticatedPlayerIdentity() {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        Player player = mock(Player.class);
        MarketTransactionRecord record = recordWithEffect();
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        when(player.hasPermission("stonks.admin.investigate")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(OPERATOR_ID);
        when(player.getName()).thenReturn("operator");
        when(journal.getRecord(TRANSACTION_ID)).thenReturn(Optional.of(record));
        when(journal.appendEvidence(any())).thenReturn(MarketTransactionJournal.EvidenceAppendResult.RECORDED);

        new ReconcileCommand(plugin).execute(player, new String[]{
                "investigate", "evidence", TRANSACTION_ID, EFFECT_ID,
                "operator_attestation", "provider-ref", "request-1", "reviewed receipt"});

        var captor = org.mockito.ArgumentCaptor.forClass(ReconciliationEvidence.class);
        verify(journal).appendEvidence(captor.capture());
        assertTrue(captor.getValue().getOperatorId().equals(OPERATOR_ID.toString()));
        assertTrue(captor.getValue().getOperatorName().equals("operator"));
        verify(player).sendMessage("Investigation evidence RECORDED; it is not proof of financial settlement.");
    }

    @Test
    void existingEvidenceIdentityIsReusedForRetry() {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        Player player = mock(Player.class);
        MarketTransactionRecord record = mock(MarketTransactionRecord.class);
        when(record.getEffects()).thenReturn(recordWithEffect().getEffects());
        ReconciliationEvidence existing = new ReconciliationEvidence(
                "44444444-4444-4444-4444-444444444444", TRANSACTION_ID, EFFECT_ID,
                ReconciliationEvidenceType.OPERATOR_ATTESTATION, "provider-ref", OPERATOR_ID.toString(),
                "operator", "reviewed receipt", 123L, "request-1");
        when(record.getReconciliationEvidenceView()).thenReturn(List.of(existing));
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        when(player.hasPermission("stonks.admin.investigate")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(OPERATOR_ID);
        when(player.getName()).thenReturn("operator");
        when(journal.getRecord(TRANSACTION_ID)).thenReturn(Optional.of(record));
        when(journal.appendEvidence(any())).thenReturn(MarketTransactionJournal.EvidenceAppendResult.ALREADY_RECORDED);

        new ReconcileCommand(plugin).execute(player, new String[]{
                "investigate", "evidence", TRANSACTION_ID, EFFECT_ID,
                "operator_attestation", "provider-ref", "request-1", "reviewed receipt"});

        var captor = org.mockito.ArgumentCaptor.forClass(ReconciliationEvidence.class);
        verify(journal).appendEvidence(captor.capture());
        assertTrue(captor.getValue().getEvidenceId().equals(existing.getEvidenceId()));
        assertTrue(captor.getValue().getCreatedAt() == existing.getCreatedAt());
    }

    @Test
    void unsafeFormattingIsRejectedAndPersistedValuesAreRedacted() {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        Player player = mock(Player.class);
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        when(player.hasPermission("stonks.admin.investigate")).thenReturn(true);

        new ReconcileCommand(plugin).execute(player, new String[]{
                "investigate", "evidence", TRANSACTION_ID, EFFECT_ID,
                "operator_attestation", "provider§c-ref", "request", "statement"});

        verify(journal, never()).appendEvidence(any());
        verify(player).sendMessage("Invalid investigation evidence.");

        MarketTransactionRecord record = mock(MarketTransactionRecord.class);
        when(record.getTransactionId()).thenReturn(TRANSACTION_ID);
        when(record.getOperation()).thenReturn("BUY§c");
        when(record.getStatus()).thenReturn(me.geraldr12.persistence.MarketTransactionStatus.RECONCILIATION_REQUIRED);
        when(record.getEffects()).thenReturn(List.of());
        ReconciliationEvidence evidence = new ReconciliationEvidence(
                "44444444-4444-4444-4444-444444444444", TRANSACTION_ID, EFFECT_ID,
                ReconciliationEvidenceType.OPERATOR_ATTESTATION, "ref‮hidden", OPERATOR_ID.toString(),
                "operator", "statement", 123L, "request");
        when(record.getReconciliationEvidenceView()).thenReturn(List.of(evidence));
        when(record.getReconciliationAuditView()).thenReturn(List.of());
        when(journal.getRecord(TRANSACTION_ID)).thenReturn(Optional.of(record));
        when(journal.isBlocking(record)).thenReturn(true);

        new ReconcileCommand(plugin).execute(player, new String[]{
                "investigate", "inspect", TRANSACTION_ID});

        verify(player).sendMessage("transaction=" + TRANSACTION_ID
                + " operation=[redacted] status=RECONCILIATION_REQUIRED blocking=true evidence=1");
        verify(player).sendMessage("evidence type=OPERATOR_ATTESTATION external=[redacted] operator=operator"
                + " operatorId=" + OPERATOR_ID + " request=request timestamp=123");
    }

    @Test
    void realJournalPersistsEvidenceAndKeepsRetryIdempotent() throws Exception {
        File root = Files.createTempDirectory("stonks-command-evidence").toFile();
        MarketTransactionJournal journal = new MarketTransactionJournal(root);
        MarketTransactionRecord record = realRecord(journal);
        String effectId = record.getEffects().get(0).getEffectId();
        Stonks plugin = mock(Stonks.class);
        Player player = mock(Player.class);
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        when(player.hasPermission("stonks.admin.investigate")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(OPERATOR_ID);
        when(player.getName()).thenReturn("operator");
        ReconcileCommand command = new ReconcileCommand(plugin);
        String[] arguments = {"investigate", "evidence", record.getTransactionId(), effectId,
                "external_audit_evidence", "ticket-123", "request-1", "reviewed receipt"};

        command.execute(player, arguments);
        verify(player).sendMessage("Investigation evidence RECORDED; it is not proof of financial settlement.");

        MarketTransactionRecord loaded = new MarketTransactionJournal(root)
                .getRecord(record.getTransactionId()).orElseThrow();
        assertEquals(1, loaded.getReconciliationEvidenceView().size());
        assertEquals(1, loaded.getReconciliationAuditView().size());
        ReconciliationEvidence stored = loaded.getReconciliationEvidenceView().get(0);
        assertEquals(record.getTransactionId(), stored.getTransactionId());
        assertEquals(effectId, stored.getEffectId());
        assertEquals("request-1", stored.getIdempotencyKey());
        assertEquals(OPERATOR_ID.toString(), stored.getOperatorId());
        assertEquals("operator", stored.getOperatorName());
        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, loaded.getStatus());
        assertEquals(MarketTransactionEffectState.AMBIGUOUS, loaded.getEffects().get(0).getState());
        assertTrue(new MarketTransactionJournal(root).isBlocking(loaded));

        command.execute(player, arguments);
        verify(player).sendMessage("Investigation evidence ALREADY_RECORDED; it is not proof of financial settlement.");
        assertEquals(1, new MarketTransactionJournal(root).getRecord(record.getTransactionId()).orElseThrow()
                .getReconciliationEvidenceView().size());

        String[] conflict = arguments.clone();
        conflict[7] = "different claim";
        command.execute(player, conflict);
        verify(player).sendMessage("Investigation evidence CONFLICT: the request identity has different evidence.");
        ReconciliationEvidence retained = new MarketTransactionJournal(root).getRecord(record.getTransactionId())
                .orElseThrow().getReconciliationEvidenceView().get(0);
        assertEquals("reviewed receipt", retained.getStatement());
    }

    @Test
    void invalidEvidenceDoesNotReachJournal() {
        Stonks plugin = mock(Stonks.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        Player player = mock(Player.class);
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        when(player.hasPermission("stonks.admin.investigate")).thenReturn(true);

        new ReconcileCommand(plugin).execute(player, new String[]{
                "investigate", "evidence", "not-a-uuid", EFFECT_ID,
                "operator_attestation", "reference", "request", "statement"});

        verify(journal, never()).appendEvidence(any());
        verify(player).sendMessage("Invalid investigation evidence.");
    }

    private static MarketTransactionRecord realRecord(MarketTransactionJournal journal) {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(TRANSACTION_ID);
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
        journal.prepareCurrentSchema(record);
        journal.requestEffect(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL, 10, 0.0, 2);
        journal.recordEffectOutcome(record, record.getEffects().get(0).getEffectId(),
                MarketTransactionEffectState.AMBIGUOUS, "uncertain effect");
        journal.markReconciliationRequired(record, "uncertain effect");
        return record;
    }

    private static MarketTransactionRecord recordWithEffect() {
        MarketTransactionRecord record = new MarketTransactionRecord();
        record.setTransactionId(TRANSACTION_ID);
        record.setStatus(me.geraldr12.persistence.MarketTransactionStatus.RECONCILIATION_REQUIRED);
        record.setEffects(List.of(MarketTransactionEffect.requested(
                TRANSACTION_ID, me.geraldr12.persistence.MarketTransactionEffectType.MARKET_IMPACT, 10, 0, 0)));
        record.getEffects().get(0).setEffectId(EFFECT_ID);
        return record;
    }
}
