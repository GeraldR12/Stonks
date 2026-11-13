package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateCommandTest {

    private Stonks plugin;
    private Player player;
    private Economy economy;
    private CompaniesService companies;
    private PlayersService players;

    @BeforeEach
    void setUp() {
        plugin = mock(Stonks.class);
        player = mock(Player.class);
        economy = mock(Economy.class);
        companies = mock(CompaniesService.class);
        players = mock(PlayersService.class);
        Messages messages = mock(Messages.class);
        FileConfiguration config = mock(FileConfiguration.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("CreateCommandTest"));
        when(plugin.getEconomy()).thenReturn(economy);
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getPlayersService()).thenReturn(players);
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isJournalReady()).thenReturn(true);
        try {
            File journalRoot = Files.createTempDirectory("stonks-create-journal").toFile();
            MarketTransactionJournal journal = new MarketTransactionJournal(journalRoot);
            when(plugin.getTransactionJournal()).thenReturn(journal);
            MarketTransactionLocks.initialize(journal);
            assertTrue(MarketTransactionLocks.activate(plugin, journal));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        when(messages.getPluginPrefix()).thenReturn("");
        when(messages.getInsufficientMoney()).thenReturn("Insufficient funds: {0}");
        when(messages.getCreatedCompany()).thenReturn("Created {0}");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

@Test
    void revokedLifecyclePreventsCreateReservation() {
        MarketTransactionLocks.revoke();

        new CreateCommand(plugin).onCommand(player, new String[]{"name", "1", "10", "2.0"});

        verify(companies, never()).reserveCompanyId(any());
        verify(economy, never()).has(any(org.bukkit.OfflinePlayer.class), anyDouble());
        verify(economy, never()).withdrawPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
        assertTrue(MarketTransactionLocks.activeAdmissionCount() == 0);
    }

@Test
    void concurrentRevocationAfterCreateAdmissionCompletesSafely() throws Exception {
        when(companies.getCompanyCreationTax(10, 2.0, 1)).thenReturn(22.0);
        when(economy.has(player, 22.0)).thenReturn(true);
        when(economy.withdrawPlayer(player, 22.0))
                .thenReturn(new EconomyResponse(22.0, 78, EconomyResponse.ResponseType.SUCCESS, null));
        when(companies.saveReservedCompany(any())).thenReturn(true);
        when(companies.removeSharesFromCompany(anyLong(), anyInt())).thenReturn(true);
        when(players.addSharesToPlayer(any(), any(), anyInt())).thenReturn(true);
        CompanyDao reserved = mock(CompanyDao.class);
        when(reserved.getId()).thenReturn(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> commandResult = new AtomicReference<>();
        when(companies.reserveCompanyId(any(CompanyDao.class))).thenAnswer(invocation -> {
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (Throwable t) {
                failure.set(t);
                throw t;
            }
            return reserved;
        });

        Thread worker = new Thread(() -> {
            try {
                commandResult.set(new CreateCommand(plugin).onCommand(
                        player, new String[]{"name", "1", "10", "2.0"}));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Thread revoker = null;
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            revoker = new Thread(MarketTransactionLocks::revoke);
            revoker.start();
            revoker.join(5000);
            assertTrue(!revoker.isAlive());
            assertTrue(MarketTransactionLocks.tryAdmit(plugin.getTransactionJournal()) == null);
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            release.countDown();
            worker.join(5000);
            assertTrue(!worker.isAlive());
            assertTrue(failure.get() == null, () -> "worker failed: " + failure.get());
            assertTrue(Boolean.TRUE.equals(commandResult.get()));
            verify(economy, times(1)).withdrawPlayer(player, 22.0);
            verify(companies, times(1)).saveReservedCompany(reserved);
            verify(companies, times(1)).removeSharesFromCompany(1, 10);
            verify(players, times(1)).addSharesToPlayer(any(), eq(reserved), eq(10));
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            release.countDown();
            if (revoker != null) revoker.join(5000);
            worker.join(5000);
        }
    }

    @Test
    void postReservationReconciliationLookupFailureFailsClosedWithoutFinancialEffects() throws Exception {
        when(companies.getCompanyCreationTax(10, 2.0, 1)).thenReturn(22.0);
        CompanyDao reserved = mock(CompanyDao.class);
        when(reserved.getId()).thenReturn(1);
        when(companies.reserveCompanyId(any(CompanyDao.class))).thenReturn(reserved);

        // 1st call: initialize()'s readiness probe. 2nd call: the pre-admission isFinanciallyReady check.
        // Only the 3rd call (the post-reservation reconciliation check) should throw.
        MarketTransactionJournal spyJournal = spy(plugin.getTransactionJournal());
        doCallRealMethod()
                .doCallRealMethod()
                .doThrow(new IllegalStateException("corrupt journal entry"))
                .when(spyJournal).getUnresolvedRecords();
        when(plugin.getTransactionJournal()).thenReturn(spyJournal);
        MarketTransactionLocks.initialize(spyJournal);
        assertTrue(MarketTransactionLocks.activate(plugin, spyJournal));

        boolean result = new CreateCommand(plugin).onCommand(player, new String[]{"name", "1", "10", "2.0"});

        assertTrue(result);
        verify(companies, times(1)).reserveCompanyId(any(CompanyDao.class));
        verify(spyJournal, never()).prepare(any());
        verify(economy, never()).has(any(org.bukkit.OfflinePlayer.class), anyDouble());
        verify(economy, never()).withdrawPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
        verify(companies, never()).saveReservedCompany(any());
        verify(companies, never()).removeSharesFromCompany(anyLong(), anyInt());
        verify(players, never()).addSharesToPlayer(any(), any(), anyInt());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void invalidRiskRejectedBeforeTaxOrVault() {
        new CreateCommand(plugin).onCommand(player, new String[]{"name", "0", "10", "2.0"});
        verifyNoInteractions(economy);
        verify(companies, never()).getCompanyCreationTax(anyLong(), anyDouble(), anyInt());
    }

    @Test
    void invalidPriceRejectedBeforeVault() {
        new CreateCommand(plugin).onCommand(player, new String[]{"name", "1", "10", "NaN"});
        verifyNoInteractions(economy);
    }

    @Test
    void invalidTaxStopsBeforeVault() {
        when(companies.getCompanyCreationTax(10, 2.0, 1)).thenReturn(Double.NaN);
        new CreateCommand(plugin).onCommand(player, new String[]{"name", "1", "10", "2.0"});
        verifyNoInteractions(economy);
    }

    @Test
    void unavailableReadinessPreventsIdReservationAndFinancialEffects() {
        when(plugin.isJournalReady()).thenReturn(false);

        new CreateCommand(plugin).onCommand(player, new String[]{"name", "1", "10", "2.0"});

        verify(companies, never()).reserveCompanyId(any());
        verify(economy, never()).has(any(org.bukkit.OfflinePlayer.class), anyDouble());
        verify(economy, never()).withdrawPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
    }

    @Test
    void readinessProbeFailurePreventsIdReservationAndFinancialEffects() {
        when(plugin.isJournalReady()).thenThrow(new IllegalStateException("unavailable"));

        new CreateCommand(plugin).onCommand(player, new String[]{"name", "1", "10", "2.0"});

        verify(companies, never()).reserveCompanyId(any());
        verify(economy, never()).has(any(org.bukkit.OfflinePlayer.class), anyDouble());
        verify(economy, never()).withdrawPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
    }

    @Test
    void admittedCreateKeepsAdmissionThroughReservationAndFinancialWork() {
        when(companies.getCompanyCreationTax(10, 2.0, 1)).thenReturn(22.0);
        CompanyDao reserved = mock(CompanyDao.class);
        when(reserved.getId()).thenReturn(1);
        when(companies.reserveCompanyId(any(CompanyDao.class))).thenAnswer(invocation -> {
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            return reserved;
        });
        when(economy.has(player, 22.0)).thenAnswer(invocation -> {
            assertEquals(1, MarketTransactionLocks.activeAdmissionCount());
            return false;
        });

        new CreateCommand(plugin).onCommand(player, new String[]{"name", "1", "10", "2.0"});

        verify(companies, times(1)).reserveCompanyId(any(CompanyDao.class));
        verify(economy, times(1)).has(player, 22.0);
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

    @Test
    void failedWithdrawalDoesNotCreateCompany() {
        when(companies.getCompanyCreationTax(10, 2.0, 1)).thenReturn(22.0);
        when(economy.has(player, 22.0)).thenReturn(true);
        when(economy.withdrawPlayer(player, 22.0)).thenReturn(new EconomyResponse(0, 100, EconomyResponse.ResponseType.FAILURE, "failed"));
        CompanyDao reserved = mock(CompanyDao.class);
        when(reserved.getId()).thenReturn(1);
        when(companies.reserveCompanyId(any(CompanyDao.class))).thenReturn(reserved);
        when(players.addSharesToPlayer(any(), any(), anyInt())).thenReturn(true);
        when(companies.saveReservedCompany(any())).thenReturn(true);
        when(companies.removeSharesFromCompany(anyLong(), anyInt())).thenReturn(true);
        new CreateCommand(plugin).onCommand(player, new String[]{"name", "1", "10", "2.0"});
        verify(economy, times(1)).withdrawPlayer(player, 22.0);
        verify(companies, never()).createPlayerCompany(anyString(), anyInt(), anyInt(), anyDouble(), any(), anyString());
    }
}
