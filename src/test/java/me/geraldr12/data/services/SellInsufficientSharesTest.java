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

class SellInsufficientSharesTest {
    private final Stonks plugin = mock(Stonks.class);
    private final CompaniesService companies = mock(CompaniesService.class);
    private final PlayersService players = mock(PlayersService.class);
    private final MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
    private final SellTransactionService.EconomyMutator economy = mock(SellTransactionService.EconomyMutator.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private final InvestmentDao investment = new InvestmentDao(0, 2, 10.0);

    @BeforeEach
    void setUp() {
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getPlayersService()).thenReturn(players);
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        when(journal.getUnresolvedRecords()).thenReturn(List.of());
        CompanyDao company = mock(CompanyDao.class);
        when(companies.getCompanyById(0)).thenReturn(company);
        when(company.getCurrentSharePrice()).thenReturn(10.0);
        when(economy.fractionalDigits()).thenReturn(2);
        when(player.getUniqueId()).thenReturn(playerId);
        when(players.getInvestmentInCompany(playerId, 0)).thenReturn(Optional.of(investment));
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
    }

    @AfterEach
    void tearDown() {
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        MarketTransactionLocks.revoke();
    }

    @Test
    void sellingThreeWithTwoSharesRejectsWithoutEffects() {
        SellTransactionResult result = new SellTransactionService(plugin, economy).execute(playerId, player, 0, 3);

        assertEquals(SellTransactionResult.Status.INSUFFICIENT_SHARES, result.status());
        assertNull(result.transactionId());
        assertEquals(2, investment.getSharesAmount());
        verify(economy).fractionalDigits();
        verifyNoMoreInteractions(economy);
        assertNoShareOrJournalEffects();
    }

    @Test
    void sellingOneWithoutInvestmentRejectsWithoutEffects() {
        when(players.getInvestmentInCompany(playerId, 0)).thenReturn(Optional.empty());

        SellTransactionResult result = new SellTransactionService(plugin, economy).execute(playerId, player, 0, 1);

        assertEquals(SellTransactionResult.Status.INSUFFICIENT_SHARES, result.status());
        assertNull(result.transactionId());
        verify(economy).fractionalDigits();
        verifyNoMoreInteractions(economy);
        assertNoShareOrJournalEffects();
    }

    @Test
    void commandShowsPlayerNoActionsInsteadOfCompanyInsufficientActions() {
        Messages messages = mock(Messages.class);
        when(plugin.getMessages()).thenReturn(messages);
        when(messages.getPluginPrefix()).thenReturn("Stonks > ");
        when(messages.getPlayerNoActions()).thenReturn("You don't have enough stocks.");
        when(messages.getInsufficientActions()).thenReturn("This company doesn't have enough stocks to sell.");
        Economy vault = mock(Economy.class);
        when(plugin.getEconomy()).thenReturn(vault);
        when(vault.fractionalDigits()).thenReturn(2);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class,
                withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            assertTrue(new SellCommand(plugin).onCommand(player, new String[]{"0", "3"}));

            verify(player).sendMessage("Stonks > You don't have enough stocks.");
            verify(player, never()).sendMessage("Stonks > This company doesn't have enough stocks to sell.");
            verify(messages).getPlayerNoActions();
            verify(messages, never()).getInsufficientActions();
            verify(vault).fractionalDigits();
            verifyNoMoreInteractions(vault);
            assertEquals(2, investment.getSharesAmount());
            assertNoShareOrJournalEffects();
        }
    }

    private void assertNoShareOrJournalEffects() {
        verify(journal, never()).prepareCurrentSchema(any());
        verify(players).getInvestmentInCompany(playerId, 0);
        verifyNoMoreInteractions(players);
        verify(companies).getCompanyById(0);
        verify(companies).getMarketImpactPerShare();
        verify(companies).getTradingTax();
        verify(companies).getTradingMinimum();
        verifyNoMoreInteractions(companies);
    }
}
