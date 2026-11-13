package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.economy.EconomyMutationOutcome;
import me.geraldr12.economy.EconomyMutationResult;
import me.geraldr12.persistence.MarketTransactionEffectState;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionStatus;
import me.geraldr12.utils.MarketTransactionLocks;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuyTransactionRejectionTest {
    @TempDir
    Path directory;

    @AfterEach
    void revokeAdmission() {
        MarketTransactionLocks.revoke();
    }

    @Test
    void insufficientFundsRejectsBeforeJournalOrWithdrawal() {
        Fixture fixture = new Fixture();
        when(fixture.economy.has(fixture.player, 25.0)).thenReturn(false);

        BuyTransactionResult result = fixture.execute(2);

        assertEquals(BuyTransactionResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(25.0, result.totalValue());
        assertNull(result.transactionId());
        verify(fixture.economy).has(fixture.player, 25.0);
        fixture.verifyNoMutation();
    }

    @Test
    void insufficientSharesRejectsBeforeJournalOrWithdrawal() {
        Fixture fixture = new Fixture();

        BuyTransactionResult result = fixture.execute(101);

        assertEquals(BuyTransactionResult.Status.INSUFFICIENT_SHARES, result.status());
        fixture.verifyNoMutation();
        verify(fixture.economy, never()).has(any(), anyDouble());
    }

    @Test
    void unlimitedSharesReachFundsValidation() {
        Fixture fixture = new Fixture();
        when(fixture.company.getAvailableShares()).thenReturn(-1);
        when(fixture.economy.has(fixture.player, 1262.5)).thenReturn(false);

        BuyTransactionResult result = fixture.execute(101);

        assertEquals(BuyTransactionResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(1262.5, result.totalValue());
        fixture.verifyNoMutation();
    }

    @Test
    void definiteWithdrawalRejectionAbortsWithoutReconciliation() {
        Fixture fixture = new Fixture();
        when(fixture.economy.has(fixture.player, 25.0)).thenReturn(true);
        when(fixture.economy.withdraw(fixture.player, 25.0)).thenReturn(
                new EconomyMutationResult(EconomyMutationOutcome.DEFINITE_REJECTED,
                        25.0, 0.0, "test", EconomyResponse.ResponseType.FAILURE, "rejected"));

        BuyTransactionResult result = fixture.execute(2);

        assertEquals(BuyTransactionResult.Status.PAYMENT_REJECTED, result.status());
        var captor = org.mockito.ArgumentCaptor.forClass(me.geraldr12.persistence.MarketTransactionRecord.class);
        verify(fixture.journal).abort(captor.capture(), anyString());
        var record = captor.getValue();
        assertTrue(fixture.journal.getRecord(result.transactionId()).isEmpty(), "aborted record must be archived");
        assertEquals(MarketTransactionStatus.ABORTED, record.getStatus());
        assertEquals(1, record.getEffects().size());
        assertEquals(MarketTransactionEffectState.DEFINITE_REJECTED, record.getEffects().get(0).getState());
        verify(fixture.economy, times(1)).withdraw(fixture.player, 25.0);
        verify(fixture.journal).prepareCurrentSchema(any());
        verify(fixture.journal, never()).markReconciliationRequired(any(), anyString());
        verify(fixture.companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

    @Test
    void legacyResultFactoriesKeepUnknownTotal() {
        assertTrue(Double.isNaN(new BuyTransactionResult(
                BuyTransactionResult.Status.SUCCESS, 7, 2, "transaction").totalValue()));
        assertTrue(Double.isNaN(BuyTransactionResult.of(
                BuyTransactionResult.Status.SUCCESS, 7, 2, "transaction").totalValue()));
        assertEquals(25.0, BuyTransactionResult.of(
                BuyTransactionResult.Status.INSUFFICIENT_FUNDS, 7, 2, null, 25.0).totalValue());
    }

    private final class Fixture {
        final UUID playerId = UUID.randomUUID();
        final Stonks plugin = mock(Stonks.class);
        final OfflinePlayer player = mock(OfflinePlayer.class);
        final CompaniesService companies = mock(CompaniesService.class);
        final CompanyDao company = mock(CompanyDao.class);
        final BuyTransactionService.EconomyMutator economy = mock(BuyTransactionService.EconomyMutator.class);
        final MarketTransactionJournal journal = spy(new MarketTransactionJournal(directory.toFile()));

        Fixture() {
            when(plugin.getTransactionJournal()).thenReturn(journal);
            when(plugin.isJournalReady()).thenReturn(true);
            when(plugin.getCompaniesService()).thenReturn(companies);
            when(companies.getCompanyById(7)).thenReturn(company);
            when(company.getAvailableShares()).thenReturn(100);
            when(company.getCurrentSharePrice()).thenReturn(12.5);
            when(economy.fractionalDigits()).thenReturn(-1);
            MarketTransactionLocks.initialize(journal);
            assertTrue(MarketTransactionLocks.activate(plugin, journal));
        }

        BuyTransactionResult execute(int quantity) {
            return new BuyTransactionService(plugin, economy).execute(playerId, player, 7, quantity);
        }

        void verifyNoMutation() {
            verify(journal, never()).prepareCurrentSchema(any());
            verify(economy, never()).withdraw(any(), anyDouble());
            verify(economy, never()).deposit(any(), anyDouble());
            verify(companies, never()).removeSharesFromCompanyTyped(anyLong(), anyInt());
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        }
    }
}
