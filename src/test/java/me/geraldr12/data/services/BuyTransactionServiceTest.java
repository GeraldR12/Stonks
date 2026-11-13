package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.economy.EconomyMutationOutcome;
import me.geraldr12.economy.EconomyMutationResult;
import me.geraldr12.persistence.MarketTransactionEffectType;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import me.geraldr12.persistence.MarketTransactionEffect;
import me.geraldr12.persistence.MarketTransactionEffectState;
import me.geraldr12.persistence.MarketTransactionRecord;
import java.util.EnumMap;

class BuyTransactionServiceTest {
@Test
    void invalidInputsAreRejectedBeforeJournalAdmissionOrFinancialEffects() throws Exception {
        Fixture fixture = new Fixture();
        BuyTransactionService service = new BuyTransactionService(fixture.plugin, fixture.economyMutator());

        BuyTransactionResult negativeCompany = service.execute(fixture.playerId, fixture.player, -1, 2);
        BuyTransactionResult zeroQuantity = service.execute(fixture.playerId, fixture.player, 7, 0);
        BuyTransactionResult negativeQuantity = service.execute(fixture.playerId, fixture.player, 7, -2);

        assertEquals(BuyTransactionResult.Status.VALIDATION_REJECTED, negativeCompany.status());
        assertEquals(-1, negativeCompany.companyId());
        assertEquals(BuyTransactionResult.Status.VALIDATION_REJECTED, zeroQuantity.status());
        assertEquals(0, zeroQuantity.quantity());
        assertEquals(BuyTransactionResult.Status.VALIDATION_REJECTED, negativeQuantity.status());
        assertEquals(-2, negativeQuantity.quantity());
        verify(fixture.plugin, never()).getTransactionJournal();
        verify(fixture.journalSpy, never()).prepareCurrentSchema(any());
        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
    }

@Test
    void revokedLifecycleBlocksBuyBeforeAdmission() throws Exception {
        Fixture fixture = happyFixture();
        MarketTransactionLocks.revoke();

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.BLOCKED_PENDING_REVIEW, result.status());
        verify(fixture.journalSpy, never()).prepareCurrentSchema(any());
        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void revocationAfterAdmissionDoesNotCancelBuy() throws Exception {
        Fixture fixture = happyFixture();
        doAnswer(invocation -> {
            MarketTransactionLocks.revoke();
            return invocation.callRealMethod();
        }).when(fixture.journalSpy).prepareCurrentSchema(any());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.SUCCESS, result.status());
        verify(fixture.economy).withdrawPlayer(fixture.player, 25.0);
        verify(fixture.journalSpy).complete(any());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void buyCompensationStillRunsAfterRevocation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        OutcomeTracker tracker = fixture.trackOutcomes();
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0))
                .thenReturn(new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.depositPlayer(fixture.player, 25.0))
                .thenReturn(new EconomyResponse(25.0, 100.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        doAnswer(invocation -> {
            MarketTransactionLocks.revoke();
            return ShareMutationResult.rejected(ShareMutationOperation.COMPANY_REMOVE,
                    null, 7L, 2, "insufficient shares");
        }).when(fixture.companies).removeSharesFromCompanyTyped(7, 2);

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.COMPENSATED, result.status());
        verify(fixture.economy).depositPlayer(fixture.player, 25.0);
        assertEquals(1, tracker.count(MarketTransactionEffectType.ECONOMY_REFUND));
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void defaultBuyServiceRejectsBeforeVaultProviderAccessOffMainThread() throws Exception {
        Fixture fixture = new Fixture();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                new BuyTransactionService(fixture.plugin).execute(fixture.playerId, fixture.player, 7, 2);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
        verifyNoInteractions(fixture.economy);
    }

@Test
    void concurrentRevocationAfterBuyAdmissionAllowsCompletion() throws Exception {
        Fixture fixture = happyFixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<BuyTransactionResult> result = new AtomicReference<>();
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(fixture.journalSpy).prepareCurrentSchema(any());

        Thread worker = new Thread(() -> result.set(new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2)));
        Thread revoker = null;
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            revoker = new Thread(MarketTransactionLocks::revoke);
            revoker.start();
            revoker.join(5000);
            assertFalse(revoker.isAlive());
            assertEquals(null, MarketTransactionLocks.tryAdmit(fixture.journalSpy));
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(BuyTransactionResult.Status.SUCCESS, result.get().status());
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            release.countDown();
            if (revoker != null) revoker.join(5000);
            worker.join(5000);
        }
    }

    @Test
    void zeroDigitProviderUsesCanonicalUnitAndTotal() throws Exception {
        Fixture fixture = happyFixture();
        when(fixture.economy.fractionalDigits()).thenReturn(0);
        CompanyDao company = fixture.companies.getCompanyById(7);
        when(company.getCurrentSharePrice()).thenReturn(464.3759780887496);
        when(fixture.economy.withdrawPlayer(fixture.player, 928.0)).thenReturn(
                new EconomyResponse(928.0, 1072.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.companies.removeSharesFromCompanyTyped(7, 2)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 2,
                        100L, 98L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(464.0), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 2, 0L, 2L, 464.0, null, 464.0));

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.SUCCESS, result.status());
        verify(fixture.economy).withdrawPlayer(fixture.player, 928.0);
        verify(fixture.players).addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(464.0), anyInt());
    }

    @Test
    void multiUnitZeroDigitProviderRoundsTotalDownAndRecordsEffectiveUnit() throws Exception {
        Fixture fixture = happyFixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        when(fixture.economy.fractionalDigits()).thenReturn(0);
        CompanyDao company = fixture.companies.getCompanyById(7);
        when(company.getCurrentSharePrice()).thenReturn(10.75);
        when(fixture.economy.withdrawPlayer(fixture.player, 32.0)).thenReturn(
                new EconomyResponse(32.0, 68.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.companies.removeSharesFromCompanyTyped(7, 3)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 3,
                        100L, 97L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(32.0 / 3), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 3, 0L, 3L, 32.0 / 3, null, 32.0 / 3));
        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 3);

        assertEquals(BuyTransactionResult.Status.SUCCESS, result.status());
        ArgumentCaptor<MarketTransactionRecord> recordCaptor = ArgumentCaptor.forClass(MarketTransactionRecord.class);
        verify(fixture.journalSpy).prepareCurrentSchema(recordCaptor.capture());
        MarketTransactionRecord record = recordCaptor.getValue();
        assertEquals(32.0 / 3, record.getQuoteUnitSharePrice());
        assertEquals(32.0, record.getQuoteTotalValue());
        assertEquals(3, record.getQuoteQuantity());
        verify(fixture.economy).withdrawPlayer(fixture.player, 32.0);
        verify(fixture.players).addSharesToPlayerTyped(any(), anyLong(), eq(3), eq(32.0 / 3), anyInt());
        verify(fixture.companies).applyMarketImpact(any(), eq(7L), eq(3), eq(true));
    }

    @Test
    void positiveProviderPrecisionRoundsDown() throws Exception {
        Fixture fixture = happyFixture();
        when(fixture.economy.fractionalDigits()).thenReturn(2);
        CompanyDao company = fixture.companies.getCompanyById(7);
        when(company.getCurrentSharePrice()).thenReturn(10.759);
        when(fixture.economy.withdrawPlayer(fixture.player, 10.75)).thenReturn(
                new EconomyResponse(10.75, 89.25, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.companies.removeSharesFromCompanyTyped(7, 1)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 1,
                        100L, 99L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(10.75), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 1, 0L, 1L, 10.75, null, 10.75));

        new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 1);

        verify(fixture.economy).withdrawPlayer(fixture.player, 10.75);
        verify(fixture.players).addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(10.75), anyInt());
    }

    @Test
    void minusOneProviderPrecisionPreservesRawPrice() throws Exception {
        Fixture fixture = happyFixture();
        when(fixture.economy.fractionalDigits()).thenReturn(-1);
        CompanyDao company = fixture.companies.getCompanyById(7);
        when(company.getCurrentSharePrice()).thenReturn(10.759);
        when(fixture.economy.withdrawPlayer(fixture.player, 10.759)).thenReturn(
                new EconomyResponse(10.759, 89.241, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.companies.removeSharesFromCompanyTyped(7, 1)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 1,
                        100L, 99L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(10.759), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 1, 0L, 1L, 10.759, null, 10.759));

        new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 1);

        verify(fixture.economy).withdrawPlayer(fixture.player, 10.759);
        verify(fixture.players).addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(10.759), anyInt());
    }

    @Test
    void zeroDigitSingleUnitProviderResponseUsesCanonicalAmount() throws Exception {
        Fixture fixture = happyFixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        when(fixture.economy.fractionalDigits()).thenReturn(0);
        CompanyDao company = fixture.companies.getCompanyById(7);
        when(company.getCurrentSharePrice()).thenReturn(464.3759780887496);
        when(fixture.economy.withdrawPlayer(fixture.player, 464.0)).thenReturn(
                new EconomyResponse(464.0, 1536.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.companies.removeSharesFromCompanyTyped(7, 1)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 1,
                        100L, 99L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(464.0), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 1, 0L, 1L, 464.0, null, 464.0));
        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 1);

        assertEquals(BuyTransactionResult.Status.SUCCESS, result.status());
        ArgumentCaptor<MarketTransactionRecord> recordCaptor = ArgumentCaptor.forClass(MarketTransactionRecord.class);
        verify(fixture.journalSpy).prepareCurrentSchema(recordCaptor.capture());
        MarketTransactionRecord record = recordCaptor.getValue();
        assertEquals(464.0, record.getQuoteUnitSharePrice());
        assertEquals(464.0, record.getQuoteTotalValue());
        assertEquals(464.0, record.getValue());
        verify(fixture.economy).withdrawPlayer(fixture.player, 464.0);
        verify(fixture.players).addSharesToPlayerTyped(any(), anyLong(), eq(1), eq(464.0), anyInt());
    }

    @Test
    void nullPlayerIsRejectedBeforeLockAndFinancialEffects() throws Exception {
        Fixture fixture = new Fixture();

        BuyTransactionResult result = assertDoesNotThrow(() ->
                new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                        .execute(null, fixture.player, 7, 2));

        assertEquals(BuyTransactionResult.Status.VALIDATION_REJECTED, result.status());
        verify(fixture.plugin, never()).getTransactionJournal();
        verify(fixture.journalSpy, never()).prepareCurrentSchema(any());
        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void nullEconomyActorIsRejectedBeforeJournalAndFinancialEffects() throws Exception {
        Fixture fixture = new Fixture();

        BuyTransactionResult result = assertDoesNotThrow(() ->
                new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                        .execute(fixture.playerId, null, 7, 2));

        assertEquals(BuyTransactionResult.Status.VALIDATION_REJECTED, result.status());
        verify(fixture.plugin, never()).getTransactionJournal();
        verify(fixture.journalSpy, never()).prepareCurrentSchema(any());
        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void negativeProviderPrecisionFailsBeforeJournal() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.economy.fractionalDigits()).thenReturn(-2);

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.VALIDATION_REJECTED, result.status());
        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void normalizationToZeroFailsBeforeJournal() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.economy.fractionalDigits()).thenReturn(0);
        CompanyDao company = fixture.companies.getCompanyById(7);
        when(company.getCurrentSharePrice()).thenReturn(0.75);

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 1);

        assertEquals(BuyTransactionResult.Status.VALIDATION_REJECTED, result.status());
        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void companyIdZeroCanProduceTypedResult() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.companies.getCompanyById(0)).thenReturn(null);

        BuyTransactionResult result = assertDoesNotThrow(() ->
                new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                        .execute(fixture.playerId, fixture.player, 0, 1));

        assertEquals(BuyTransactionResult.Status.VALIDATION_REJECTED, result.status());
        assertEquals(0, result.companyId());
        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    void buyTransactionResultAcceptsZeroAndPreservesInvalidValidationMetadata() {
        assertDoesNotThrow(() -> BuyTransactionResult.of(
                BuyTransactionResult.Status.VALIDATION_REJECTED, 0, 1, null));
        BuyTransactionResult negativeCompany = BuyTransactionResult.of(
                BuyTransactionResult.Status.VALIDATION_REJECTED, -1, 1, null);
        assertEquals(-1, negativeCompany.companyId());
        BuyTransactionResult zeroQuantity = BuyTransactionResult.of(
                BuyTransactionResult.Status.VALIDATION_REJECTED, 0, 0, null);
        assertEquals(0, zeroQuantity.quantity());
        BuyTransactionResult negativeQuantity = BuyTransactionResult.of(
                BuyTransactionResult.Status.VALIDATION_REJECTED, 0, -1, null);
        assertEquals(-1, negativeQuantity.quantity());
        assertThrows(IllegalArgumentException.class, () -> BuyTransactionResult.of(
                BuyTransactionResult.Status.SUCCESS, -1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> BuyTransactionResult.of(
                BuyTransactionResult.Status.SUCCESS, 0, 0, null));
    }

    @Test
    void buyQuoteAcceptsZeroAndRejectsNegativeCompanyIds() {
        UUID playerId = UUID.randomUUID();
        assertDoesNotThrow(() -> new BuyQuote(0, playerId, 1, 10.0, 10.0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BuyQuote(-1, playerId, 1, 10.0, 10.0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BuyQuote(0, playerId, 0, 10.0, 0.0, 1));
    }

    private static final class OutcomeTracker {
        private final EnumMap<MarketTransactionEffectType, Integer> counts =
                new EnumMap<>(MarketTransactionEffectType.class);

        void record(MarketTransactionRecord record, String effectId) {
            MarketTransactionEffect effect = record.getEffects().stream()
                    .filter(candidate -> effectId.equals(candidate.getEffectId()))
                    .findFirst().orElseThrow();
            counts.merge(effect.getType(), 1, Integer::sum);
        }

        int count(MarketTransactionEffectType type) {
            return counts.getOrDefault(type, 0);
        }
    }
    @Test
    void debitRequestFailureDoesNotInvokeVaultOrLocalMutations() throws Exception {
        Fixture fixture = new Fixture();
        MarketTransactionJournal journal = spy(fixture.journal);
        doReturn(false).when(journal).requestEffect(any(), any(), anyInt(), anyDouble(), anyInt());
        when(fixture.plugin.getTransactionJournal()).thenReturn(journal);

        new BuyTransactionService(fixture.plugin, fixture.economyMutator()).execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    void typedResultKeepsTransactionSemanticsOutOfPlayerMessaging() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0))
                .thenReturn(new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        when(fixture.companies.removeSharesFromCompanyTyped(anyLong(), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE,
                        null, 7L, 2, 100L, 98L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 2, 0L, 2L, 12.5, null, 12.5));
        when(fixture.companies.applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean())).thenReturn(true);

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertNotNull(result);
        assertEquals(BuyTransactionResult.Status.SUCCESS, result.status());
        verify(fixture.player, never()).sendMessage(anyString());
    }

    @Test
    void debitAmbiguousFreezesBeforeLocalMutation() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0))
                .thenReturn(new EconomyResponse(0.0, 75.0, EconomyResponse.ResponseType.FAILURE, "provider unavailable"));
        when(fixture.economy.getName()).thenReturn("test");
        fixture.withdrawOutcome = EconomyMutationOutcome.AMBIGUOUS;

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
        verify(fixture.economy, times(1)).withdrawPlayer(fixture.player, 25.0);
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    void companyRequestFailureDoesNotInvokeRemovalOrRefund() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doAnswer(invocation -> invocation.getArgument(1, MarketTransactionEffectType.class) != MarketTransactionEffectType.COMPANY_SHARE_REMOVAL)
                .when(fixture.journalSpy).requestEffect(any(), any(), anyInt(), anyDouble(), anyInt());
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0))
                .thenReturn(new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, result.status());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void companyDefiniteRejectionRefundsExactlyOnceAsCompensated() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        OutcomeTracker tracker = fixture.trackOutcomes();
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0))
                .thenReturn(new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.depositPlayer(fixture.player, 25.0))
                .thenReturn(new EconomyResponse(25.0, 100.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        when(fixture.companies.removeSharesFromCompanyTyped(7, 2))
                .thenReturn(ShareMutationResult.rejected(ShareMutationOperation.COMPANY_REMOVE,
                        null, 7L, 2, "insufficient shares"));

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.COMPENSATED, result.status());
        verify(fixture.economy, times(1)).withdrawPlayer(fixture.player, 25.0);
        verify(fixture.economy, times(1)).depositPlayer(fixture.player, 25.0);
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        assertEquals(1, tracker.count(MarketTransactionEffectType.ECONOMY_DEBIT));
        assertEquals(1, tracker.count(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL));
        assertEquals(1, tracker.count(MarketTransactionEffectType.ECONOMY_REFUND));
        assertEquals(0, tracker.count(MarketTransactionEffectType.PLAYER_SHARE_ADD));
        assertEquals(0, tracker.count(MarketTransactionEffectType.COMPANY_SHARE_RESTORATION));
        assertEquals(0, tracker.count(MarketTransactionEffectType.MARKET_IMPACT));
    }

    @Test
    void canonicalCaseBRefundUsesCanonicalTotalExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        when(fixture.economy.fractionalDigits()).thenReturn(0);
        CompanyDao company = fixture.companies.getCompanyById(7);
        when(company.getCurrentSharePrice()).thenReturn(10.75);
        when(fixture.economy.withdrawPlayer(fixture.player, 32.0)).thenReturn(
                new EconomyResponse(32.0, 68.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.depositPlayer(fixture.player, 32.0)).thenReturn(
                new EconomyResponse(32.0, 100.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.companies.removeSharesFromCompanyTyped(7, 3)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 3,
                        100L, 97L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), eq(32.0 / 3), anyInt()))
                .thenReturn(ShareMutationResult.rejected(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 3, "limit"));
        when(fixture.companies.restoreSharesToCompanyTyped(eq(7L), any()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_RESTORE,
                        null, 7L, 3, 97L, 100L, null, null, null));

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 3);

        assertEquals(BuyTransactionResult.Status.COMPENSATED, result.status());
        verify(fixture.economy, times(1)).withdrawPlayer(fixture.player, 32.0);
        verify(fixture.economy, times(1)).depositPlayer(fixture.player, 32.0);
        verify(fixture.economy, never()).withdrawPlayer(fixture.player, 32.25);
        verify(fixture.economy, never()).depositPlayer(fixture.player, 32.25);
        verify(fixture.companies).restoreSharesToCompanyTyped(eq(7L), any());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void happyPathUsesTypedMutationsAndCompletes() throws Exception {
        Fixture fixture = new Fixture();
        OutcomeTracker tracker = fixture.trackOutcomes();
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0))
                .thenReturn(new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        when(fixture.companies.removeSharesFromCompanyTyped(anyLong(), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE,
                        null, 7L, 2, 100L, 98L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 2, 0L, 2L, 12.5, null, 12.5));
        when(fixture.companies.applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean())).thenReturn(true);

        new BuyTransactionService(fixture.plugin, fixture.economyMutator()).execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.economy, times(1)).withdrawPlayer(fixture.player, 25.0);
        verify(fixture.companies, times(1)).removeSharesFromCompanyTyped(7, 2);
        verify(fixture.players, times(1)).addSharesToPlayerTyped(fixture.playerId, 7, 2, 12.5, 0);
        verify(fixture.companies, times(1)).applyMarketImpact(fixture.playerId, 7, 2, true);
        verify(fixture.journalSpy, times(4)).recordEffectOutcome(any(), anyString(), any(), any());
        assertEquals(1, tracker.count(MarketTransactionEffectType.ECONOMY_DEBIT));
        assertEquals(1, tracker.count(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL));
        assertEquals(1, tracker.count(MarketTransactionEffectType.PLAYER_SHARE_ADD));
        assertEquals(1, tracker.count(MarketTransactionEffectType.MARKET_IMPACT));
    }

    @Test
    void debitDefiniteRejectedAbortsWithoutLocalMutation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        OutcomeTracker tracker = fixture.trackOutcomes();
        fixture.withdrawOutcome = EconomyMutationOutcome.DEFINITE_REJECTED;
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.FAILURE, "insufficient funds"));
        when(fixture.economy.getName()).thenReturn("test");

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.PAYMENT_REJECTED, result.status());
        assertEquals(1, tracker.count(MarketTransactionEffectType.ECONOMY_DEBIT));
        verify(fixture.economy).withdrawPlayer(fixture.player, 25.0);
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void debitDefiniteRejectedAbortPersistenceFailureReturnsReconciliationRequired() throws Exception {
        Fixture fixture = new Fixture();
        fixture.withdrawOutcome = EconomyMutationOutcome.DEFINITE_REJECTED;
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.FAILURE, "rejected"));
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doReturn(false).when(fixture.journalSpy).abort(any(), anyString());
        doReturn(true).when(fixture.journalSpy)
                .markReconciliationRequired(any(MarketTransactionRecord.class), anyString());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
        verify(fixture.journalSpy).abort(any(), anyString());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void debitDefiniteRejectedAbortAndReconciliationPersistenceFailureReturnsCritical() throws Exception {
        Fixture fixture = new Fixture();
        fixture.withdrawOutcome = EconomyMutationOutcome.DEFINITE_REJECTED;
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.FAILURE, "rejected"));
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doReturn(false).when(fixture.journalSpy).abort(any(), anyString());
        doReturn(false).when(fixture.journalSpy).markReconciliationRequired(any(), anyString());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, result.status());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void playerDefiniteRejectRestoresThenRefundsAndCompensates() throws Exception {
        Fixture fixture = caseBFixture();
        OutcomeTracker tracker = fixture.trackOutcomes();
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.depositPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 100.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        ShareMutationResult removed = ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE,
                null, 7L, 2, 100L, 98L, null, null, null);
        when(fixture.companies.removeSharesFromCompanyTyped(7, 2)).thenReturn(removed);
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(ShareMutationResult.rejected(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 2, "limit"));
        when(fixture.companies.restoreSharesToCompanyTyped(eq(7L), any()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_RESTORE,
                        null, 7L, 2, 98L, 100L, null, null, null));

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.COMPENSATED, result.status());
        verify(fixture.companies).restoreSharesToCompanyTyped(eq(7L), any());
        verify(fixture.economy).depositPlayer(fixture.player, 25.0);
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
        assertEquals(1, tracker.count(MarketTransactionEffectType.ECONOMY_DEBIT));
        assertEquals(1, tracker.count(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL));
        assertEquals(1, tracker.count(MarketTransactionEffectType.PLAYER_SHARE_ADD));
        assertEquals(1, tracker.count(MarketTransactionEffectType.COMPANY_SHARE_RESTORATION));
        assertEquals(1, tracker.count(MarketTransactionEffectType.ECONOMY_REFUND));
        assertEquals(0, tracker.count(MarketTransactionEffectType.MARKET_IMPACT));
    }

    @Test
    void playerConfirmedOutcomePersistenceFailureStopsFlow() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        AtomicInteger outcomeWrites = new AtomicInteger();
        doAnswer(invocation -> outcomeWrites.incrementAndGet() < 3)
                .when(fixture.journalSpy).recordEffectOutcome(any(), anyString(), any(), any());
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        when(fixture.companies.removeSharesFromCompanyTyped(7, 2)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE,
                        null, 7L, 2, 100L, 98L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 2, 0L, 2L, 12.5, null, 12.5));

        new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.players).addSharesToPlayerTyped(any(), eq(7L), eq(2), eq(12.5), anyInt());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void debitOutcomePersistenceFailureStopsBeforeCompanyMutation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doReturn(false).when(fixture.journalSpy).recordEffectOutcome(any(), anyString(), any(), any());
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");

        new BuyTransactionService(fixture.plugin, fixture.economyMutator()).execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.economy, times(1)).withdrawPlayer(fixture.player, 25.0);
        verify(fixture.journalSpy, times(1)).recordEffectOutcome(any(), anyString(), any(), any());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void companyRemovalOutcomePersistenceFailureStopsBeforePlayerAdd() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> writes.incrementAndGet() != 2)
                .when(fixture.journalSpy).recordEffectOutcome(any(), anyString(), any(), any());
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        when(fixture.companies.removeSharesFromCompanyTyped(7, 2)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 2,
                        100L, 98L, null, null, null));

        new BuyTransactionService(fixture.plugin, fixture.economyMutator()).execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.companies, times(1)).removeSharesFromCompanyTyped(7, 2);
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.journalSpy, times(2)).recordEffectOutcome(any(), anyString(), any(), any());
    }

    @Test
    void restorationRequestFailureDoesNotRestoreOrRefund() throws Exception {
        Fixture fixture = caseBFixture();
        doAnswer(invocation -> invocation.getArgument(1, MarketTransactionEffectType.class)
                != MarketTransactionEffectType.COMPANY_SHARE_RESTORATION)
                .when(fixture.journalSpy).requestEffect(any(), any(), anyInt(), anyDouble(), anyInt());

        new BuyTransactionService(fixture.plugin, fixture.economyMutator()).execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.companies, never()).restoreSharesToCompanyTyped(anyLong(), any());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void restorationAmbiguousDoesNotRefund() throws Exception {
        Fixture fixture = caseBFixture();
        when(fixture.companies.restoreSharesToCompanyTyped(eq(7L), any()))
                .thenReturn(ShareMutationResult.ambiguous(ShareMutationOperation.COMPANY_RESTORE,
                        null, 7L, 2, 98L, 100L, null, null, null, "save unknown"));

        new BuyTransactionService(fixture.plugin, fixture.economyMutator()).execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.companies, times(1)).restoreSharesToCompanyTyped(eq(7L), any());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void restorationOutcomePersistenceFailureDoesNotRefund() throws Exception {
        Fixture fixture = caseBFixture();
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> writes.incrementAndGet() != 4)
                .when(fixture.journalSpy).recordEffectOutcome(any(), anyString(), any(), any());

        new BuyTransactionService(fixture.plugin, fixture.economyMutator()).execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.companies, times(1)).restoreSharesToCompanyTyped(eq(7L), any());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void refundRequestFailureDoesNotDeposit() throws Exception {
        Fixture fixture = caseAFixture();
        doAnswer(invocation -> invocation.getArgument(1, MarketTransactionEffectType.class)
                != MarketTransactionEffectType.ECONOMY_REFUND)
                .when(fixture.journalSpy).requestEffect(any(), any(), anyInt(), anyDouble(), anyInt());

        new BuyTransactionService(fixture.plugin, fixture.economyMutator()).execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void refundAmbiguousDoesNotRetry() throws Exception {
        Fixture fixture = caseAFixture();
        fixture.depositOutcome = EconomyMutationOutcome.AMBIGUOUS;

        BuyTransactionService service = new BuyTransactionService(fixture.plugin, fixture.economyMutator());
        service.execute(fixture.playerId, fixture.player, 7, 2);
        service.execute(fixture.playerId, fixture.player, 7, 2);

        verify(fixture.economy, times(1)).depositPlayer(fixture.player, 25.0);
    }

    @Test
    void refundOutcomePersistenceFailureDoesNotClaimCompensated() throws Exception {
        Fixture fixture = caseAFixture();
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> writes.incrementAndGet() != 3)
                .when(fixture.journalSpy).recordEffectOutcome(any(), anyString(), any(), any());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
        verify(fixture.economy, times(1)).depositPlayer(fixture.player, 25.0);
    }

    @Test
    void marketImpactOutcomePersistenceFailureDoesNotReplayFinancialEffects() throws Exception {
        Fixture fixture = happyFixture();
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> writes.incrementAndGet() != 4)
                .when(fixture.journalSpy).recordEffectOutcome(any(), anyString(), any(), any());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.COMMITTED_MARKET_RECONCILIATION, result.status());
        verify(fixture.economy, times(1)).withdrawPlayer(fixture.player, 25.0);
        verify(fixture.companies, times(1)).removeSharesFromCompanyTyped(7, 2);
        verify(fixture.players, times(1)).addSharesToPlayerTyped(any(), eq(7L), eq(2), eq(12.5), anyInt());
        verify(fixture.companies, times(1)).applyMarketImpact(any(), eq(7L), eq(2), eq(true));
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void companyRemovalExceptionBecomesAmbiguousAndStops() throws Exception {
        Fixture fixture = happyFixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        fixture.trackOutcomes();
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doThrow(new IllegalStateException("company failure"))
                .when(fixture.companies).removeSharesFromCompanyTyped(7, 2);

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
        assertEquals(1, fixture.outcomeTracker.count(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL));
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void playerAddExceptionBecomesAmbiguousAndStops() throws Exception {
        Fixture fixture = happyFixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        fixture.trackOutcomes();
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doThrow(new IllegalStateException("player failure"))
                .when(fixture.players).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
        assertEquals(1, fixture.outcomeTracker.count(MarketTransactionEffectType.PLAYER_SHARE_ADD));
        verify(fixture.companies, never()).restoreSharesToCompanyTyped(anyLong(), any());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void companyRestorationExceptionBecomesAmbiguousAndStops() throws Exception {
        Fixture fixture = caseBFixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        fixture.trackOutcomes();
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doThrow(new IllegalStateException("restoration failure"))
                .when(fixture.companies).restoreSharesToCompanyTyped(anyLong(), any());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
        assertEquals(1, fixture.outcomeTracker.count(MarketTransactionEffectType.COMPANY_SHARE_RESTORATION));
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void companyRemovalExceptionOutcomeSaveFailureReturnsUnresolved() throws Exception {
        Fixture fixture = happyFixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doThrow(new IllegalStateException("company failure"))
                .when(fixture.companies).removeSharesFromCompanyTyped(7, 2);
        doReturn(false).when(fixture.journalSpy)
                .recordEffectOutcome(any(), anyString(), eq(MarketTransactionEffectState.AMBIGUOUS), anyString());
        doReturn(false).when(fixture.journalSpy).markReconciliationRequired(any(), anyString());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, result.status());
        verify(fixture.players, never()).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void playerAddExceptionOutcomeSaveFailureReturnsUnresolved() throws Exception {
        Fixture fixture = happyFixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doThrow(new IllegalStateException("player failure"))
                .when(fixture.players).addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt());
        doReturn(false).when(fixture.journalSpy)
                .recordEffectOutcome(any(), anyString(), eq(MarketTransactionEffectState.AMBIGUOUS), anyString());
        doReturn(false).when(fixture.journalSpy).markReconciliationRequired(any(), anyString());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, result.status());
        verify(fixture.companies, never()).restoreSharesToCompanyTyped(anyLong(), any());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void companyRestorationExceptionOutcomeSaveFailureReturnsUnresolved() throws Exception {
        Fixture fixture = caseBFixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        doThrow(new IllegalStateException("restoration failure"))
                .when(fixture.companies).restoreSharesToCompanyTyped(anyLong(), any());
        doReturn(false).when(fixture.journalSpy)
                .recordEffectOutcome(any(), anyString(), eq(MarketTransactionEffectState.AMBIGUOUS), anyString());
        doReturn(false).when(fixture.journalSpy).markReconciliationRequired(any(), anyString());

        BuyTransactionResult result = new BuyTransactionService(fixture.plugin, fixture.economyMutator())
                .execute(fixture.playerId, fixture.player, 7, 2);

        assertEquals(BuyTransactionResult.Status.CRITICAL_UNRESOLVED, result.status());
        verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        verify(fixture.companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    private static Fixture happyFixture() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        when(fixture.companies.removeSharesFromCompanyTyped(7, 2)).thenReturn(
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 2,
                        100L, 98L, null, null, null));
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 2, 0L, 2L, 12.5, null, 12.5));
        when(fixture.companies.applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean())).thenReturn(true);
        return fixture;
    }

    private static Fixture caseAFixture() throws Exception {
        Fixture fixture = new Fixture();
        fixture.journalSpy = spy(fixture.journal);
        MarketTransactionLocks.initialize(fixture.journalSpy);
        assertTrue(MarketTransactionLocks.activate(fixture.plugin, fixture.journalSpy));
        when(fixture.plugin.getTransactionJournal()).thenReturn(fixture.journalSpy);
        when(fixture.economy.withdrawPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 75.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.depositPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 100.0, EconomyResponse.ResponseType.SUCCESS, null));
        when(fixture.economy.getName()).thenReturn("test");
        when(fixture.companies.removeSharesFromCompanyTyped(7, 2)).thenReturn(
                ShareMutationResult.rejected(ShareMutationOperation.COMPANY_REMOVE, null, 7L, 2, "rejected"));
        return fixture;
    }

    private static Fixture caseBFixture() throws Exception {
        Fixture fixture = happyFixture();
        when(fixture.players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(ShareMutationResult.rejected(ShareMutationOperation.PLAYER_ADD,
                        fixture.playerId, 7L, 2, "limit"));
        when(fixture.companies.restoreSharesToCompanyTyped(eq(7L), any()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_RESTORE,
                        null, 7L, 2, 98L, 100L, null, null, null));
        when(fixture.economy.depositPlayer(fixture.player, 25.0)).thenReturn(
                new EconomyResponse(25.0, 100.0, EconomyResponse.ResponseType.SUCCESS, null));
        return fixture;
    }

    private static final class Fixture {
        final UUID playerId = UUID.randomUUID();
        final Stonks plugin = mock(Stonks.class);
        final Player player = mock(Player.class);
        final Economy economy = mock(Economy.class);
        final CompaniesService companies = mock(CompaniesService.class);
        final PlayersService players = mock(PlayersService.class);
        final Messages messages = mock(Messages.class);
        final FileConfiguration config = mock(FileConfiguration.class);
        final MarketTransactionJournal journal = new MarketTransactionJournal(temp());
        MarketTransactionJournal journalSpy;
        OutcomeTracker outcomeTracker;
        EconomyMutationOutcome withdrawOutcome = EconomyMutationOutcome.CONFIRMED;
        EconomyMutationOutcome depositOutcome = EconomyMutationOutcome.CONFIRMED;

        Fixture() throws Exception {
            journalSpy = spy(journal);
            when(plugin.getEconomy()).thenReturn(economy);
            when(economy.fractionalDigits()).thenReturn(-1);
            when(plugin.getCompaniesService()).thenReturn(companies);
            when(plugin.getPlayersService()).thenReturn(players);
            when(plugin.getMessages()).thenReturn(messages);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.isJournalReady()).thenReturn(true);
            when(plugin.getTransactionJournal()).thenReturn(journalSpy);
            when(config.getInt("Stonks.Limits.MaxSharesPerPlayer")).thenReturn(0);
            when(messages.getPluginPrefix()).thenReturn("");
            when(messages.getBoughtActions()).thenReturn("Bought {0}");
            when(player.getUniqueId()).thenReturn(playerId);
            CompanyDao company = mock(CompanyDao.class);
            when(company.getId()).thenReturn(7);
            when(company.getAvailableShares()).thenReturn(100);
            when(company.getCurrentSharePrice()).thenReturn(12.5);
            when(company.isBankrupt()).thenReturn(false);
            when(companies.getCompanyById(7)).thenReturn(company);
                MarketTransactionLocks.initialize(journalSpy);
                assertTrue(MarketTransactionLocks.activate(plugin, journalSpy));
        }

        OutcomeTracker trackOutcomes() {
            outcomeTracker = new OutcomeTracker();
            doAnswer(invocation -> {
                outcomeTracker.record(invocation.getArgument(0), invocation.getArgument(1));
                return invocation.callRealMethod();
            }).when(journalSpy).recordEffectOutcome(any(), anyString(), any(), any());
            when(plugin.getTransactionJournal()).thenReturn(journalSpy);
            return outcomeTracker;
        }

        BuyTransactionService.EconomyMutator economyMutator() {
            return new BuyTransactionService.EconomyMutator() {
                @Override
                public int fractionalDigits() {
                    return economy.fractionalDigits();
                }

                @Override
                public EconomyMutationResult withdraw(OfflinePlayer player, double amount) {
                    EconomyResponse response = economy.withdrawPlayer(player, amount);
                    if (response == null) {
                        return new EconomyMutationResult(EconomyMutationOutcome.AMBIGUOUS,
                                amount, null, economy.getName(), null, "no response");
                    }
                    return new EconomyMutationResult(withdrawOutcome, amount, response.amount,
                            economy.getName(), response.type, response.errorMessage);
                }

                @Override
                public EconomyMutationResult deposit(OfflinePlayer player, double amount) {
                    EconomyResponse response = economy.depositPlayer(player, amount);
                    if (response == null) {
                        return new EconomyMutationResult(EconomyMutationOutcome.AMBIGUOUS,
                                amount, null, economy.getName(), null, "no response");
                    }
                    return new EconomyMutationResult(depositOutcome, amount, response.amount,
                            economy.getName(), response.type, response.errorMessage);
                }
            };
        }

        private static java.io.File temp() {
            try { return Files.createTempDirectory("stonks-buy-service").toFile(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
