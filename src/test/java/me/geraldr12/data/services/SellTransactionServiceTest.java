package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.economy.EconomyMutationOutcome;
import me.geraldr12.economy.EconomyMutationResult;
import me.geraldr12.persistence.MarketTransactionEffectType;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.MarketTransactionLocks;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.File;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SellTransactionServiceTest {

    private Stonks plugin;
    private MarketTransactionJournal journal;
    private CompaniesService companiesService;
    private PlayersService playersService;
    private SellTransactionService.EconomyMutator economy;
    private SellTransactionService service;

    private UUID playerId;
    private OfflinePlayer economyActor;
    private long companyId = 0L;

    @BeforeEach
    void setUp() {
        plugin = mock(Stonks.class);
        journal = mock(MarketTransactionJournal.class);
        companiesService = mock(CompaniesService.class);
        playersService = mock(PlayersService.class);
        economy = mock(SellTransactionService.EconomyMutator.class);

        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.getCompaniesService()).thenReturn(companiesService);
        when(plugin.getPlayersService()).thenReturn(playersService);

        when(economy.fractionalDigits()).thenReturn(2);

        service = new SellTransactionService(plugin, economy);

        playerId = UUID.randomUUID();
        economyActor = mock(OfflinePlayer.class);
        when(economyActor.getUniqueId()).thenReturn(playerId);
    }


    private void prepareReadyJournal() {
        when(plugin.isJournalReady()).thenReturn(true);
        when(journal.getUnresolvedRecords()).thenReturn(java.util.List.of());
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
    }

    private void prepareCompany(double price) {
        CompanyDao dao = mock(CompanyDao.class);
        when(dao.getId()).thenReturn((int) companyId);
        when(dao.isBankrupt()).thenReturn(false);
        when(dao.getCurrentSharePrice()).thenReturn(price);
        when(companiesService.getCompanyById(companyId)).thenReturn(dao);
    }

    private void preparePlayerInvestments(int quantity) {
        InvestmentDao inv = new InvestmentDao(companyId, quantity, 10.0);
        when(playersService.getInvestmentInCompany(playerId, companyId)).thenReturn(Optional.of(inv));
    }

    private void prepareJournal() {
        prepareReadyJournal();
        when(journal.prepareCurrentSchema(any())).thenAnswer(i -> i.getArgument(0));
        when(journal.requestEffect(any(), any(), anyInt(), anyDouble(), anyInt())).thenReturn(true);
        when(journal.recordEffectOutcome(any(), any(), any(), any())).thenReturn(true);
        when(journal.complete(any())).thenReturn(true);
        when(journal.saveCompensated(any())).thenReturn(true);
        when(journal.abort(any(), anyString())).thenReturn(true);
        when(journal.markReconciliationRequired(any(), anyString())).thenReturn(true);
    }

@Test
    void revocationBeforeAdmissionBlocksWithoutFinancialEffects() {
        prepareReadyJournal();
        MarketTransactionLocks.revoke();

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 1);

        assertEquals(SellTransactionResult.Status.BLOCKED_PENDING_REVIEW, result.status());
        verify(journal, never()).prepareCurrentSchema(any());
        verify(playersService, never()).removeSharesFromPlayerTyped(any(), anyLong(), anyInt());
        verify(economy, never()).deposit(any(), anyDouble());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void revokedLifecycleBlocksSellBeforeAdmissionWithoutEffects() {
        prepareCompany(10.0);
        preparePlayerInvestments(5);
        prepareReadyJournal();
        MarketTransactionLocks.revoke();

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 1);

        assertEquals(SellTransactionResult.Status.BLOCKED_PENDING_REVIEW, result.status());
        verify(journal, never()).prepareCurrentSchema(any());
        verify(playersService, never()).removeSharesFromPlayerTyped(any(), anyLong(), anyInt());
        verify(companiesService, never()).addSharesToCompanyTyped(anyLong(), anyInt());
        verify(economy, never()).deposit(any(), anyDouble());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void revocationAfterAdmissionDoesNotCancelSell() {
        prepareCompany(10.0);
        preparePlayerInvestments(5);
        prepareJournal();
        doAnswer(invocation -> {
            MarketTransactionLocks.revoke();
            return invocation.getArgument(0);
        }).when(journal).prepareCurrentSchema(any());
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 1))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE,
                        playerId, companyId, 1, 5L, 4L, null, 10.0, null));
        when(companiesService.addSharesToCompanyTyped(companyId, 1))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD,
                        null, companyId, 1, 90L, 91L, null, null, null));
        when(economy.deposit(economyActor, 10.0))
                .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED,
                        10.0, 10.0, null, null, null));
        when(companiesService.applyMarketImpact(playerId, companyId, 1, false)).thenReturn(true);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 1);

        assertEquals(SellTransactionResult.Status.SUCCESS, result.status());
        verify(economy).deposit(economyActor, 10.0);
        verify(journal).complete(any());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void sellCompensationStillRunsAfterRevocation() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();
        ShareMutationResult playerRemoved = ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3,
                10L, 7L, null, 10.0, null);
        ShareMutationResult companyAdded = ShareMutationResult.confirmed(
                ShareMutationOperation.COMPANY_ADD, null, companyId, 3,
                90L, 93L, null, null, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(playerRemoved);
        when(companiesService.addSharesToCompanyTyped(companyId, 3)).thenReturn(companyAdded);
        when(economy.deposit(economyActor, 30.0)).thenAnswer(invocation -> {
            MarketTransactionLocks.revoke();
            return new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED,
                    30.0, null, null, null, "err");
        });
        when(companiesService.removeConfirmedCompanyAddTyped(eq(companyId), any()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD_RESTORE,
                        null, companyId, 3, 93L, 90L, null, null, null));
        when(playersService.restoreRemovedPlayerSharesTyped(any()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE_RESTORE,
                        playerId, companyId, 3, 7L, 10L, null, 10.0, null));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);

        assertEquals(SellTransactionResult.Status.COMPENSATED, result.status());
        verify(companiesService).removeConfirmedCompanyAddTyped(eq(companyId), any());
        verify(playersService).restoreRemovedPlayerSharesTyped(any());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void concurrentRevocationAfterSellAdmissionAllowsCompletion() throws Exception {
        prepareCompany(10.0);
        preparePlayerInvestments(5);
        prepareJournal();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<SellTransactionResult> result = new AtomicReference<>();
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return invocation.getArgument(0);
        }).when(journal).prepareCurrentSchema(any());
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 1))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE,
                        playerId, companyId, 1, 5L, 4L, null, 10.0, null));
        when(companiesService.addSharesToCompanyTyped(companyId, 1))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD,
                        null, companyId, 1, 90L, 91L, null, null, null));
        when(economy.deposit(economyActor, 10.0))
                .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED,
                        10.0, 10.0, null, null, null));
        when(companiesService.applyMarketImpact(playerId, companyId, 1, false)).thenReturn(true);

        Thread worker = new Thread(() -> result.set(service.execute(playerId, economyActor, companyId, 1)));
        Thread revoker = null;
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            revoker = new Thread(MarketTransactionLocks::revoke);
            revoker.start();
            revoker.join(5000);
            assertFalse(revoker.isAlive());
            assertEquals(null, MarketTransactionLocks.tryAdmit(journal));
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(SellTransactionResult.Status.SUCCESS, result.get().status());
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            release.countDown();
            if (revoker != null) revoker.join(5000);
            worker.join(5000);
        }
    }

    @Test
    void validationRejectsNullPlayer() {
        SellTransactionResult res = service.execute(null, economyActor, companyId, 1);
        assertEquals(SellTransactionResult.Status.VALIDATION_REJECTED, res.status());
        verifyNoInteractions(journal, playersService, companiesService, economy);
    }

    @Test
    void validationRejectsNullEconomyActor() {
        SellTransactionResult res = service.execute(playerId, null, companyId, 1);
        assertEquals(SellTransactionResult.Status.VALIDATION_REJECTED, res.status());
        verifyNoInteractions(journal, playersService, companiesService, economy);
    }

    @Test
    void validationRejectsNegativeCompanyId() {
        SellTransactionResult res = service.execute(playerId, economyActor, -1L, 1);
        assertEquals(SellTransactionResult.Status.VALIDATION_REJECTED, res.status());
        verifyNoInteractions(journal, playersService, companiesService, economy);
    }

    @Test
    void validationAcceptsZeroCompanyId() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(playersService.removeSharesFromPlayerTyped(playerId, 0L, 1))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, 0L, 1, 10L, 9L, null, 10.0, null));
        when(companiesService.addSharesToCompanyTyped(0L, 1))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, 0L, 1, 90L, 91L, null, null, null));
        when(economy.deposit(economyActor, 10.0))
            .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED, 10.0, 10.0, null, null, null));
        when(companiesService.applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean())).thenReturn(true);

        SellTransactionResult res = service.execute(playerId, economyActor, 0L, 1);
        assertEquals(SellTransactionResult.Status.SUCCESS, res.status());
    }

    @Test
    void validationRejectsZeroQuantity() {
        SellTransactionResult res = service.execute(playerId, economyActor, companyId, 0);
        assertEquals(SellTransactionResult.Status.VALIDATION_REJECTED, res.status());
        verifyNoInteractions(journal, playersService, companiesService, economy);
    }

    @Test
    void happyPathExecutesExactWorkflowAndCausalOrder() {
        prepareCompany(10.758);
        preparePlayerInvestments(10);
        prepareJournal();

        when(economy.fractionalDigits()).thenReturn(2);

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.75, null));
        when(companiesService.addSharesToCompanyTyped(companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null));
        when(economy.deposit(economyActor, 32.27))
            .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED, 32.27, 32.27, null, null, null));
        when(companiesService.applyMarketImpact(playerId, companyId, 3, false)).thenReturn(true);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.SUCCESS, result.status());

        InOrder inOrder = inOrder(journal, playersService, companiesService, economy);

        inOrder.verify(journal).requestEffect(any(), eq(MarketTransactionEffectType.PLAYER_SHARE_REMOVAL), eq(10), eq(0.0), eq(3));
        inOrder.verify(playersService).removeSharesFromPlayerTyped(playerId, companyId, 3);

        inOrder.verify(journal).requestEffect(any(), eq(MarketTransactionEffectType.COMPANY_SHARE_ADD), eq(20), eq(0.0), eq(3));
        inOrder.verify(companiesService).addSharesToCompanyTyped(companyId, 3);

        inOrder.verify(journal).requestEffect(any(), eq(MarketTransactionEffectType.ECONOMY_CREDIT), eq(30), eq(32.27), eq(3));
        inOrder.verify(economy).deposit(economyActor, 32.27);

        inOrder.verify(journal).requestEffect(any(), eq(MarketTransactionEffectType.MARKET_IMPACT), eq(70), eq(0.0), eq(3));
        inOrder.verify(companiesService).applyMarketImpact(playerId, companyId, 3, false);
    }

    @Test
    void precisionDigits0Test() {
        prepareCompany(10.75);
        preparePlayerInvestments(10);
        prepareJournal();

        when(economy.fractionalDigits()).thenReturn(0);

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null));
        when(companiesService.addSharesToCompanyTyped(companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null));
        when(economy.deposit(economyActor, 32.0))
            .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED, 32.0, 32.0, null, null, null));
        when(companiesService.applyMarketImpact(playerId, companyId, 3, false)).thenReturn(true);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.SUCCESS, result.status());

        verify(journal).requestEffect(any(), eq(MarketTransactionEffectType.ECONOMY_CREDIT), eq(30), eq(32.0), eq(3));
        verify(economy).deposit(economyActor, 32.0);
    }

    @Test
    void precisionZeroCanonicalTest() {
        prepareReadyJournal();
        prepareCompany(0.75);
        preparePlayerInvestments(10);
        when(economy.fractionalDigits()).thenReturn(0);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 1);
        assertEquals(SellTransactionResult.Status.FEE_EXCEEDS_VALUE, result.status());

        verify(journal, never()).prepareCurrentSchema(any());
        verifyNoInteractions(playersService);
        verify(economy, never()).deposit(any(), anyDouble());
        verify(companiesService, never()).addSharesToCompanyTyped(anyLong(), anyInt());
    }

    @Test
    void fractionalDigitsNegativeOnePreservesRawPrice() {
        prepareCompany(10.758);
        preparePlayerInvestments(10);
        prepareJournal();

        when(economy.fractionalDigits()).thenReturn(-1);

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 1))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 1, 10L, 9L, null, 10.758, null));
        when(companiesService.addSharesToCompanyTyped(companyId, 1))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 1, 90L, 91L, null, null, null));
        when(economy.deposit(economyActor, 10.758))
            .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED, 10.758, 10.758, null, null, null));
        when(companiesService.applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean())).thenReturn(true);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 1);
        assertEquals(SellTransactionResult.Status.SUCCESS, result.status());
        verify(economy).deposit(economyActor, 10.758);
    }

    @Test
    void fractionalDigitsLowerThanNegativeOneRejectsBeforeJournal() {
        prepareReadyJournal();
        prepareCompany(10.758);
        preparePlayerInvestments(10);
        when(economy.fractionalDigits()).thenReturn(-2);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 1);
        assertEquals(SellTransactionResult.Status.VALIDATION_REJECTED, result.status());
        verify(journal, never()).prepareCurrentSchema(any());
    }

    @Test
    void playerRemoveRequestFailureTest() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(journal.requestEffect(any(), eq(MarketTransactionEffectType.PLAYER_SHARE_REMOVAL), anyInt(), anyDouble(), anyInt())).thenReturn(false);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(playersService, never()).removeSharesFromPlayerTyped(any(), anyLong(), anyInt());
    }

    @Test
    void companyAddRequestFailureTest() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null));

        when(journal.requestEffect(any(), eq(MarketTransactionEffectType.COMPANY_SHARE_ADD), anyInt(), anyDouble(), anyInt())).thenReturn(false);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(companiesService, never()).addSharesToCompanyTyped(anyLong(), anyInt());
    }

    @Test
    void economyCreditRequestFailureTest() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null));
        when(companiesService.addSharesToCompanyTyped(companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null));

        when(journal.requestEffect(any(), eq(MarketTransactionEffectType.ECONOMY_CREDIT), anyInt(), anyDouble(), anyInt())).thenReturn(false);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(economy, never()).deposit(any(), anyDouble());
    }

    @Test
    void marketImpactRequestFailureTest() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null));
        when(companiesService.addSharesToCompanyTyped(companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null));
        when(economy.deposit(economyActor, 30.0))
            .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED, 30.0, 30.0, null, null, null));

        when(journal.requestEffect(any(), eq(MarketTransactionEffectType.MARKET_IMPACT), anyInt(), anyDouble(), anyInt())).thenReturn(false);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.MARKET_IMPACT_FAILURE_AFTER_COMMIT, result.status());

        verify(companiesService, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void companyCompRequestFailureTest() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        ShareMutationResult caRes = ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null);
        when(companiesService.addSharesToCompanyTyped(companyId, 3)).thenReturn(caRes);

        when(economy.deposit(economyActor, 30.0)).thenReturn(new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED, 30.0, null, null, null, "err"));

        when(journal.requestEffect(any(), eq(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL), anyInt(), anyDouble(), anyInt())).thenReturn(false);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(companiesService, never()).removeConfirmedCompanyAddTyped(anyLong(), any());
    }

    @Test
    void playerRestoreRequestFailureTest() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        ShareMutationResult caRes = ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null);
        when(companiesService.addSharesToCompanyTyped(companyId, 3)).thenReturn(caRes);

        when(economy.deposit(economyActor, 30.0)).thenReturn(new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED, 30.0, null, null, null, "err"));

        when(companiesService.removeConfirmedCompanyAddTyped(eq(companyId), any()))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD_RESTORE, null, companyId, 3, 93L, 90L, null, null, null));

        when(journal.requestEffect(any(), eq(MarketTransactionEffectType.PLAYER_SHARE_RESTORATION), anyInt(), anyDouble(), anyInt())).thenReturn(false);

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(playersService, never()).restoreRemovedPlayerSharesTyped(any());
    }

    @Test
    void playerRemovalRejectionAborts() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.rejected(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, "fail"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.DEFINITE_FAILURE, result.status());

        verify(journal).abort(any(), anyString());
        verify(journal, never()).requestEffect(any(), eq(MarketTransactionEffectType.COMPANY_SHARE_ADD), anyInt(), anyDouble(), anyInt());
    }

    @Test
    void playerRemovalAmbiguityRequiresReconciliation() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.ambiguous(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null, "timeout"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(journal).markReconciliationRequired(any(), anyString());
    }

    @Test
    void companyAddRejectionRestoresPlayer() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        when(companiesService.addSharesToCompanyTyped(companyId, 3))
            .thenReturn(ShareMutationResult.rejected(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, "cap"));

        when(playersService.restoreRemovedPlayerSharesTyped(any()))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE_RESTORE, playerId, companyId, 3, 7L, 10L, null, 10.0, null));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.COMPENSATED, result.status());

        verify(journal).requestEffect(any(), eq(MarketTransactionEffectType.PLAYER_SHARE_RESTORATION), eq(50), eq(0.0), eq(3));
        verify(journal).saveCompensated(any());
        verify(economy, never()).deposit(any(), anyDouble());

        ArgumentCaptor<ConfirmedPlayerRemovalEvidence> captor = ArgumentCaptor.forClass(ConfirmedPlayerRemovalEvidence.class);
        verify(playersService).restoreRemovedPlayerSharesTyped(captor.capture());
        ConfirmedPlayerRemovalEvidence ev = captor.getValue();
        assertEquals(playerId, ev.playerId());
        assertEquals(companyId, ev.companyId());
        assertEquals(10L, ev.beforeQuantity());
        assertEquals(7L, ev.afterQuantity());
    }

    @Test
    void companyAddAmbiguityRequiresReconciliation() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        when(companiesService.addSharesToCompanyTyped(companyId, 3))
            .thenReturn(ShareMutationResult.ambiguous(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null, "timeout"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(economy, never()).deposit(any(), anyDouble());
        verify(playersService, never()).restoreRemovedPlayerSharesTyped(any());
        verify(journal, never()).requestEffect(any(), eq(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL), anyInt(), anyDouble(), anyInt());
    }

    @Test
    void economyRejectionCompensatesBoth() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        ShareMutationResult caRes = ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null);
        when(companiesService.addSharesToCompanyTyped(companyId, 3)).thenReturn(caRes);

        when(economy.deposit(economyActor, 30.0)).thenReturn(new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED, 30.0, null, null, null, "err"));

        when(companiesService.removeConfirmedCompanyAddTyped(eq(companyId), any()))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD_RESTORE, null, companyId, 3, 93L, 90L, null, null, null));

        when(playersService.restoreRemovedPlayerSharesTyped(any()))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE_RESTORE, playerId, companyId, 3, 7L, 10L, null, 10.0, null));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.COMPENSATED, result.status());

        verify(journal).requestEffect(any(), eq(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL), eq(40), eq(0.0), eq(3));
        verify(journal).requestEffect(any(), eq(MarketTransactionEffectType.PLAYER_SHARE_RESTORATION), eq(50), eq(0.0), eq(3));
        verify(journal).saveCompensated(any());

        ArgumentCaptor<ConfirmedCompanyAddEvidence> compCaptor = ArgumentCaptor.forClass(ConfirmedCompanyAddEvidence.class);
        verify(companiesService).removeConfirmedCompanyAddTyped(eq(companyId), compCaptor.capture());
        ConfirmedCompanyAddEvidence compEv = compCaptor.getValue();
        assertEquals(companyId, compEv.companyId());
        assertEquals(3, compEv.requestedQuantity());
        assertEquals(90L, compEv.beforeQuantity());
        assertEquals(93L, compEv.afterQuantity());
    }

    @Test
    void companyCompRejectedRequiresReconciliation() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        ShareMutationResult caRes = ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null);
        when(companiesService.addSharesToCompanyTyped(companyId, 3)).thenReturn(caRes);

        when(economy.deposit(economyActor, 30.0)).thenReturn(new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED, 30.0, null, null, null, "err"));

        when(companiesService.removeConfirmedCompanyAddTyped(eq(companyId), any()))
            .thenReturn(ShareMutationResult.rejected(ShareMutationOperation.COMPANY_ADD_RESTORE, null, companyId, 3, "cap"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(playersService, never()).restoreRemovedPlayerSharesTyped(any());
    }

    @Test
    void companyCompAmbiguousRequiresReconciliation() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        ShareMutationResult caRes = ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null);
        when(companiesService.addSharesToCompanyTyped(companyId, 3)).thenReturn(caRes);

        when(economy.deposit(economyActor, 30.0)).thenReturn(new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED, 30.0, null, null, null, "err"));

        when(companiesService.removeConfirmedCompanyAddTyped(eq(companyId), any()))
            .thenReturn(ShareMutationResult.ambiguous(ShareMutationOperation.COMPANY_ADD_RESTORE, null, companyId, 3, 93L, 90L, null, null, null, "timeout"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(playersService, never()).restoreRemovedPlayerSharesTyped(any());
    }

    @Test
    void playerRestoreRejectedRequiresReconciliation() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        ShareMutationResult caRes = ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null);
        when(companiesService.addSharesToCompanyTyped(companyId, 3)).thenReturn(caRes);

        when(economy.deposit(economyActor, 30.0)).thenReturn(new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED, 30.0, null, null, null, "err"));

        when(companiesService.removeConfirmedCompanyAddTyped(eq(companyId), any()))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD_RESTORE, null, companyId, 3, 93L, 90L, null, null, null));

        when(playersService.restoreRemovedPlayerSharesTyped(any()))
            .thenReturn(ShareMutationResult.rejected(ShareMutationOperation.PLAYER_REMOVE_RESTORE, playerId, companyId, 3, "fail"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
    }

    @Test
    void playerRestoreAmbiguousRequiresReconciliation() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        ShareMutationResult prRes = ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null);
        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3)).thenReturn(prRes);

        ShareMutationResult caRes = ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null);
        when(companiesService.addSharesToCompanyTyped(companyId, 3)).thenReturn(caRes);

        when(economy.deposit(economyActor, 30.0)).thenReturn(new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED, 30.0, null, null, null, "err"));

        when(companiesService.removeConfirmedCompanyAddTyped(eq(companyId), any()))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD_RESTORE, null, companyId, 3, 93L, 90L, null, null, null));

        when(playersService.restoreRemovedPlayerSharesTyped(any()))
            .thenReturn(ShareMutationResult.ambiguous(ShareMutationOperation.PLAYER_REMOVE_RESTORE, playerId, companyId, 3, 7L, 10L, null, 10.0, null, "timeout"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
    }

    @Test
    void economyAmbiguityNeverReversesConfirmedCredit() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null));

        when(companiesService.addSharesToCompanyTyped(companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null));

        when(economy.deposit(economyActor, 30.0)).thenReturn(new EconomyMutationResult(EconomyMutationOutcome.AMBIGUOUS, 30.0, null, null, null, "timeout"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());

        verify(journal).markReconciliationRequired(any(), eq("Vault deposit outcome is ambiguous"));
        verify(journal, never()).requestEffect(any(), eq(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL), anyInt(), anyDouble(), anyInt());
    }

    @Test
    void realJournalHappyPathPersistsCompletedSell() throws Exception {
        java.lang.reflect.Field journalField = MarketTransactionLocks.class.getDeclaredField("journal");
        java.lang.reflect.Field initializedField = MarketTransactionLocks.class.getDeclaredField("initialized");
        journalField.setAccessible(true);
        initializedField.setAccessible(true);
        MarketTransactionJournal previousJournal =
                (MarketTransactionJournal) journalField.get(null);
        boolean previousInitialized = initializedField.getBoolean(null);

        File root = Files.createTempDirectory("stonks-sell-service-journal").toFile();
        MarketTransactionJournal realJournal = new MarketTransactionJournal(root);
        try {
            when(plugin.getTransactionJournal()).thenReturn(realJournal);
            MarketTransactionLocks.initialize(realJournal);
            when(plugin.isJournalReady()).thenReturn(true);
            assertTrue(MarketTransactionLocks.activate(plugin, realJournal));
            prepareCompany(10.758);
            preparePlayerInvestments(10);

            when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
                    .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE,
                            playerId, companyId, 3, 10L, 7L, null, 10.75, null));
            when(companiesService.addSharesToCompanyTyped(companyId, 3))
                    .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD,
                            null, companyId, 3, 90L, 93L, null, null, null));
            when(economy.deposit(economyActor, 32.27))
                    .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED,
                            32.27, 32.27, null, null, null));
            when(companiesService.applyMarketImpact(playerId, companyId, 3, false)).thenReturn(true);

            SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);

            assertEquals(SellTransactionResult.Status.SUCCESS, result.status());
            assertNotNull(result.transactionId());
            verify(playersService).removeSharesFromPlayerTyped(playerId, companyId, 3);
            verify(companiesService).addSharesToCompanyTyped(companyId, 3);
            verify(economy).deposit(economyActor, 32.27);
            verify(companiesService).applyMarketImpact(playerId, companyId, 3, false);
            assertTrue(realJournal.getAllPendingOrReconciliationRecords().isEmpty());
            MarketTransactionJournal reloaded = new MarketTransactionJournal(root);
            assertTrue(reloaded.getUnresolvedRecords().isEmpty());
            assertFalse(MarketTransactionLocks.requiresReconciliation(playerId, companyId));
        } finally {
            journalField.set(null, previousJournal);
            initializedField.setBoolean(null, previousInitialized);
        }

        assertSame(previousJournal, journalField.get(null));
        assertEquals(previousInitialized, initializedField.getBoolean(null));
    }

    @Test
    void marketImpactFailureDoesNotReverseTrade() {
        prepareCompany(10.0);
        preparePlayerInvestments(10);
        prepareJournal();

        when(playersService.removeSharesFromPlayerTyped(playerId, companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId, 3, 10L, 7L, null, 10.0, null));
        when(companiesService.addSharesToCompanyTyped(companyId, 3))
            .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId, 3, 90L, 93L, null, null, null));
        when(economy.deposit(economyActor, 30.0))
            .thenReturn(new EconomyMutationResult(EconomyMutationOutcome.CONFIRMED, 30.0, 30.0, null, null, null));

        when(companiesService.applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean())).thenThrow(new RuntimeException("crash"));

        SellTransactionResult result = service.execute(playerId, economyActor, companyId, 3);
        assertEquals(SellTransactionResult.Status.MARKET_IMPACT_FAILURE_AFTER_COMMIT, result.status());

        verify(journal, never()).requestEffect(any(), eq(MarketTransactionEffectType.COMPANY_SHARE_REMOVAL), anyInt(), anyDouble(), anyInt());
        verify(journal).markReconciliationRequired(any(), eq("Market impact outcome is ambiguous"));
    }
}
