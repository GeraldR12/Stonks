package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.economy.EconomyMutationOutcome;
import me.geraldr12.economy.EconomyMutationResult;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.MarketTransactionLocks;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Share trades move gold between players and the company's funds; nothing is created. */
class CompanyFundsTradeTest {
    @TempDir Path temp;

    private Stonks plugin;
    private MarketTransactionJournal journal;
    private CompaniesService companies;
    private PlayersService players;
    private CompanyFundsService funds;
    private UUID playerId;
    private OfflinePlayer actor;
    private final long companyId = 3L;

    @BeforeEach
    void setUp() {
        plugin = mock(Stonks.class);
        journal = mock(MarketTransactionJournal.class);
        companies = mock(CompaniesService.class);
        players = mock(PlayersService.class);
        funds = new CompanyFundsService(() -> true, temp.resolve("funds/ledger.yml"));
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getPlayersService()).thenReturn(players);
        when(plugin.getCompanyFundsService()).thenReturn(funds);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.isJournalReady()).thenReturn(true);
        when(journal.getUnresolvedRecords()).thenReturn(java.util.List.of());
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
        when(journal.prepareCurrentSchema(any())).thenAnswer(i -> i.getArgument(0));
        when(journal.requestEffect(any(), any(), anyInt(), anyDouble(), anyInt())).thenReturn(true);
        when(journal.recordEffectOutcome(any(), any(), any(), any())).thenReturn(true);
        when(journal.complete(any())).thenReturn(true);
        when(journal.saveCompensated(any())).thenReturn(true);
        when(journal.abort(any(), anyString())).thenReturn(true);
        when(journal.markReconciliationRequired(any(), anyString())).thenReturn(true);
        CompanyDao company = mock(CompanyDao.class);
        when(company.getId()).thenReturn((int) companyId);
        when(company.isBankrupt()).thenReturn(false);
        when(company.getCurrentSharePrice()).thenReturn(10.0);
        when(company.getAvailableShares()).thenReturn(-1);
        when(companies.getCompanyById(companyId)).thenReturn(company);
        when(companies.applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean())).thenReturn(true);
        playerId = UUID.randomUUID();
        actor = mock(OfflinePlayer.class);
        when(actor.getUniqueId()).thenReturn(playerId);
    }

    @AfterEach
    void tearDown() {
        MarketTransactionLocks.revoke();
    }

    private static EconomyMutationResult result(EconomyMutationOutcome outcome, double amount) {
        return new EconomyMutationResult(outcome, amount, amount, "test", null, null);
    }

    private SellTransactionService.EconomyMutator sellEconomy(EconomyMutationOutcome outcome) {
        SellTransactionService.EconomyMutator economy = mock(SellTransactionService.EconomyMutator.class);
        when(economy.fractionalDigits()).thenReturn(2);
        when(economy.deposit(any(), anyDouble())).thenAnswer(i -> result(outcome, i.<Double>getArgument(1)));
        return economy;
    }

    private void holdShares(int quantity) {
        when(players.getInvestmentInCompany(playerId, companyId))
                .thenReturn(Optional.of(new InvestmentDao(companyId, quantity, 10.0)));
        when(players.removeSharesFromPlayerTyped(eq(playerId), eq(companyId), anyInt())).thenAnswer(i ->
                ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE, playerId, companyId,
                        i.<Integer>getArgument(2), 5L, 5L - i.<Integer>getArgument(2), null, 10.0, null));
        when(companies.addSharesToCompanyTyped(eq(companyId), anyInt())).thenAnswer(i ->
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD, null, companyId,
                        i.<Integer>getArgument(1), 90L, 90L + i.<Integer>getArgument(1), null, null, null));
    }

    @Test
    void saleIsRefusedBeforeAnyShareMovesWhenTheCompanyCannotPay() {
        holdShares(5);
        funds.credit(companyId, 9.0, "seed");
        SellTransactionService.EconomyMutator economy = sellEconomy(EconomyMutationOutcome.CONFIRMED);

        SellTransactionResult result = new SellTransactionService(plugin, economy)
                .execute(playerId, actor, companyId, 1);

        assertEquals(SellTransactionResult.Status.INSUFFICIENT_COMPANY_FUNDS, result.status());
        assertEquals(9.0, result.totalValue(), 1e-9);
        verify(journal, never()).prepareCurrentSchema(any());
        verify(players, never()).removeSharesFromPlayerTyped(any(), anyLong(), anyInt());
        verify(economy, never()).deposit(any(), anyDouble());
        assertEquals(9.0, funds.balance(companyId), 1e-9);
        assertFalse(funds.hasPending(companyId));
    }

    @Test
    void paidSaleComesOutOfTheCompanyFunds() {
        holdShares(5);
        funds.credit(companyId, 25.0, "seed");
        SellTransactionResult result = new SellTransactionService(plugin, sellEconomy(EconomyMutationOutcome.CONFIRMED))
                .execute(playerId, actor, companyId, 2);

        assertEquals(SellTransactionResult.Status.SUCCESS, result.status(), () -> mockingDetails(journal).getInvocations().toString());
        assertEquals(5.0, funds.balance(companyId), 1e-9);
        assertFalse(funds.hasPending(companyId));
        assertEquals(20.0, funds.paidOut(), 1e-9);
    }

    @Test
    void refusedPaymentReturnsTheGoldToTheCompany() {
        holdShares(5);
        funds.credit(companyId, 25.0, "seed");
        when(companies.removeConfirmedCompanyAddTyped(eq(companyId), any()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_ADD_RESTORE,
                        null, companyId, 2, 92L, 90L, null, null, null));
        when(players.restoreRemovedPlayerSharesTyped(any()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_REMOVE_RESTORE,
                        playerId, companyId, 2, 3L, 5L, null, 10.0, null));
        SellTransactionResult result = new SellTransactionService(plugin, sellEconomy(EconomyMutationOutcome.DEFINITE_REJECTED))
                .execute(playerId, actor, companyId, 2);

        assertNotEquals(SellTransactionResult.Status.SUCCESS, result.status());
        assertNotEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
        assertEquals(25.0, funds.balance(companyId), 1e-9);
        assertFalse(funds.hasPending(companyId));
    }

    @Test
    void ambiguousPaymentKeepsTheReservationAndPausesTheCompany() {
        holdShares(5);
        funds.credit(companyId, 25.0, "seed");
        SellTransactionResult result = new SellTransactionService(plugin, sellEconomy(EconomyMutationOutcome.AMBIGUOUS))
                .execute(playerId, actor, companyId, 2);

        assertEquals(SellTransactionResult.Status.RECONCILIATION_REQUIRED, result.status());
        assertEquals(5.0, funds.balance(companyId), 1e-9);
        assertTrue(funds.hasPending(companyId));
        assertEquals(result.transactionId(), funds.pending().get(0).id());
        assertFalse(MarketTransactionLocks.isFinanciallyReady(plugin, journal, UUID.randomUUID(), companyId));
    }

    @Test
    void disabledFundsKeepTheOldBehaviour() {
        holdShares(5);
        CompanyFundsService off = new CompanyFundsService(() -> false, temp.resolve("off/ledger.yml"));
        when(plugin.getCompanyFundsService()).thenReturn(off);
        SellTransactionResult result = new SellTransactionService(plugin, sellEconomy(EconomyMutationOutcome.CONFIRMED))
                .execute(playerId, actor, companyId, 2);
        assertEquals(SellTransactionResult.Status.SUCCESS, result.status());
    }

    @Test
    void purchaseFillsTheCompanyFundsWithTheGrossValue() {
        BuyTransactionService.EconomyMutator economy = mock(BuyTransactionService.EconomyMutator.class);
        when(economy.fractionalDigits()).thenReturn(2);
        when(economy.has(any(), anyDouble())).thenReturn(true);
        when(economy.withdraw(any(), anyDouble())).thenAnswer(i -> result(EconomyMutationOutcome.CONFIRMED, i.<Double>getArgument(1)));
        when(companies.removeSharesFromCompanyTyped(eq(companyId), anyInt())).thenAnswer(i ->
                ShareMutationResult.confirmed(ShareMutationOperation.COMPANY_REMOVE, null, companyId,
                        i.<Integer>getArgument(1), 91L, 88L, null, null, null));
        when(players.addSharesToPlayerTyped(any(), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(ShareMutationResult.confirmed(ShareMutationOperation.PLAYER_ADD,
                        playerId, companyId, 3, 0L, 3L, 10.0, null, 10.0));
        when(plugin.getConfig()).thenReturn(mock(org.bukkit.configuration.file.FileConfiguration.class));

        BuyTransactionResult result = new BuyTransactionService(plugin, economy).execute(playerId, actor, companyId, 3);

        assertEquals(BuyTransactionResult.Status.SUCCESS, result.status(), () -> mockingDetails(journal).getInvocations().toString());
        assertEquals(30.0, funds.balance(companyId), 1e-9);
    }

    @Test
    void refusedPurchaseLeavesTheFundsUntouched() {
        BuyTransactionService.EconomyMutator economy = mock(BuyTransactionService.EconomyMutator.class);
        when(economy.fractionalDigits()).thenReturn(2);
        when(economy.has(any(), anyDouble())).thenReturn(true);
        when(economy.withdraw(any(), anyDouble())).thenAnswer(i ->
                result(EconomyMutationOutcome.DEFINITE_REJECTED, i.<Double>getArgument(1)));
        when(plugin.getConfig()).thenReturn(mock(org.bukkit.configuration.file.FileConfiguration.class));

        BuyTransactionResult result = new BuyTransactionService(plugin, economy).execute(playerId, actor, companyId, 3);

        assertEquals(BuyTransactionResult.Status.PAYMENT_REJECTED, result.status());
        assertEquals(0.0, funds.balance(companyId));
    }
}
