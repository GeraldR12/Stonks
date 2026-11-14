package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.commands.SellCommand;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SellFeeExceedsValueTest {
    private final Stonks plugin = mock(Stonks.class);
    private final CompaniesService companies = mock(CompaniesService.class);
    private final PlayersService players = mock(PlayersService.class);
    private final MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
    private final SellTransactionService.EconomyMutator economy = mock(SellTransactionService.EconomyMutator.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private final CompanyDao company = mock(CompanyDao.class);

    @BeforeEach
    void setUp() {
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getPlayersService()).thenReturn(players);
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        when(journal.getUnresolvedRecords()).thenReturn(List.of());
        when(companies.getCompanyById(7)).thenReturn(company);
        when(company.getCurrentSharePrice()).thenReturn(0.5);
        when(companies.getTradingMinimum()).thenReturn(1.0);
        when(economy.fractionalDigits()).thenReturn(2);
        when(player.getUniqueId()).thenReturn(playerId);
        when(players.getInvestmentInCompany(playerId, 7)).thenReturn(Optional.of(new InvestmentDao(7, 1, 0.5)));
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
    }

    @AfterEach
    void tearDown() {
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        MarketTransactionLocks.revoke();
    }

    @Test
    void minimumFeeAboveGrossRejectsWithoutEffects() {
        assertRejectedWithoutEffects(SellTransactionResult.Status.FEE_EXCEEDS_VALUE);
    }

    @Test
    void zeroNetValueRejectsWithoutEffects() {
        when(company.getCurrentSharePrice()).thenReturn(1.0);
        assertRejectedWithoutEffects(SellTransactionResult.Status.FEE_EXCEEDS_VALUE);
    }

    @Test
    void positiveNetRoundedToZeroRejectsWithoutEffects() {
        when(company.getCurrentSharePrice()).thenReturn(1.005);
        assertRejectedWithoutEffects(SellTransactionResult.Status.FEE_EXCEEDS_VALUE);
    }

    @Test
    void invalidPriceStillUsesValidationRejected() {
        when(company.getCurrentSharePrice()).thenReturn(Double.NaN);
        assertRejectedWithoutEffects(SellTransactionResult.Status.VALIDATION_REJECTED);
    }

    @Test
    void commandExplainsMinimumFeeInsteadOfInsufficientShares() {
        Messages messages = mock(Messages.class);
        when(plugin.getMessages()).thenReturn(messages);
        when(messages.getPluginPrefix()).thenReturn("");
        when(messages.getInsufficientActions()).thenReturn("Insufficient shares");
        Economy vault = mock(Economy.class);
        when(plugin.getEconomy()).thenReturn(vault);
        when(vault.fractionalDigits()).thenReturn(2);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class,
                withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            assertTrue(new SellCommand(plugin).onCommand(player, new String[]{"7", "1"}));

            verify(player).sendMessage("This sale is too small: its value does not cover the minimum trading fee.");
            verify(vault, never()).depositPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
            verify(journal, never()).prepareCurrentSchema(any());
            verify(players, never()).removeSharesFromPlayerTyped(any(), anyLong(), anyInt());
        }
    }

    private void assertRejectedWithoutEffects(SellTransactionResult.Status expected) {
        SellTransactionResult result = new SellTransactionService(plugin, economy).execute(playerId, player, 7, 1);

        assertEquals(expected, result.status());
        assertNull(result.transactionId());
        verify(journal, never()).prepareCurrentSchema(any());
        verify(economy, never()).deposit(any(), anyDouble());
        verify(players, never()).removeSharesFromPlayerTyped(any(), anyLong(), anyInt());
        verify(companies, never()).addSharesToCompanyTyped(anyLong(), anyInt());
        verify(companies, never()).applyMarketImpact(any(), anyLong(), anyInt(), anyBoolean());
    }
}
