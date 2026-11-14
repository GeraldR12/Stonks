package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.utils.MarketTransactionLocks;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockedPendingReviewTest {
    @TempDir Path directory;

    @AfterEach
    void revokeAdmission() {
        MarketTransactionLocks.revoke();
    }

    @Test
    void buyBlockedByAnotherPlayersPendingCompanyTransactionHasNoEffects() {
        Fixture fixture = new Fixture();
        fixture.preparePending(UUID.randomUUID(), 7);

        BuyTransactionResult result = fixture.buy();

        assertEquals("BLOCKED_PENDING_REVIEW", result.status().name());
        assertNull(result.transactionId());
        fixture.verifyNoEffects();
    }

    @Test
    void sellBlockedByAnotherPlayersPendingCompanyTransactionHasNoEffects() {
        Fixture fixture = new Fixture();
        fixture.preparePending(UUID.randomUUID(), 7);

        SellTransactionResult result = fixture.sell();

        assertEquals("BLOCKED_PENDING_REVIEW", result.status().name());
        assertNull(result.transactionId());
        fixture.verifyNoEffects();
    }

    @Test
    void pendingAccountTransactionAlsoBlocksBuyAndSellInAnotherCompany() {
        Fixture fixture = new Fixture();
        fixture.preparePending(fixture.playerId, 8);

        assertEquals("BLOCKED_PENDING_REVIEW", fixture.buy().status().name());
        assertEquals("BLOCKED_PENDING_REVIEW", fixture.sell().status().name());
        fixture.verifyNoEffects();
    }

    @Test
    void buyAdmissionRevokedAfterReadinessCheckHasNoEffects() {
        Fixture fixture = new Fixture();
        fixture.revokeDuringReadinessCheck();

        assertEquals("BLOCKED_PENDING_REVIEW", fixture.buy().status().name());
        fixture.verifyNoEffects();
    }

    @Test
    void sellAdmissionRevokedAfterReadinessCheckHasNoEffects() {
        Fixture fixture = new Fixture();
        fixture.revokeDuringReadinessCheck();

        assertEquals("BLOCKED_PENDING_REVIEW", fixture.sell().status().name());
        fixture.verifyNoEffects();
    }

    private final class Fixture {
        final UUID playerId = UUID.randomUUID();
        final Stonks plugin = mock(Stonks.class);
        final OfflinePlayer player = mock(OfflinePlayer.class);
        final CompaniesService companies = mock(CompaniesService.class);
        final PlayersService players = mock(PlayersService.class);
        final BuyTransactionService.EconomyMutator buyEconomy = mock(BuyTransactionService.EconomyMutator.class);
        final SellTransactionService.EconomyMutator sellEconomy = mock(SellTransactionService.EconomyMutator.class);
        final MarketTransactionJournal journal = spy(new MarketTransactionJournal(directory.toFile()));
        MarketTransactionRecord pending;

        Fixture() {
            when(plugin.getTransactionJournal()).thenReturn(journal);
            when(plugin.isJournalReady()).thenReturn(true);
            when(plugin.getCompaniesService()).thenReturn(companies);
            when(plugin.getPlayersService()).thenReturn(players);
            MarketTransactionLocks.initialize(journal);
            assertTrue(MarketTransactionLocks.activate(plugin, journal));
        }

        void preparePending(UUID pendingPlayer, long companyId) {
            pending = new MarketTransactionRecord();
            pending.setTransactionId(UUID.randomUUID().toString());
            pending.setOperation("BUY");
            pending.setPlayerUuid(pendingPlayer.toString());
            pending.setCompanyId(companyId);
            pending.setQuantity(1);
            pending.setValue(12.5);
            pending.setQuoteCompanyId(companyId);
            pending.setQuotePlayerUuid(pendingPlayer.toString());
            pending.setQuoteQuantity(1);
            pending.setQuoteUnitSharePrice(12.5);
            pending.setQuoteTotalValue(12.5);
            pending.setQuoteTimestamp(System.currentTimeMillis());
            pending.setTimestamp(pending.getQuoteTimestamp());
            assertNotNull(journal.prepareCurrentSchema(pending));
            assertEquals(1, journal.getUnresolvedRecords().size());
            clearInvocations(journal);
        }

        void revokeDuringReadinessCheck() {
            doAnswer(invocation -> {
                MarketTransactionLocks.revoke();
                return List.of();
            }).when(journal).getUnresolvedRecords();
        }

        BuyTransactionResult buy() {
            return new BuyTransactionService(plugin, buyEconomy).execute(playerId, player, 7, 2);
        }

        SellTransactionResult sell() {
            return new SellTransactionService(plugin, sellEconomy).execute(playerId, player, 7, 2);
        }

        void verifyNoEffects() {
            verifyNoInteractions(buyEconomy, sellEconomy, companies, players);
            verify(journal, never()).prepareCurrentSchema(any());
            verify(journal, never()).prepare(any());
            if (pending != null) {
                assertEquals(List.of(pending), journal.getUnresolvedRecords());
            }
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        }
    }
}
