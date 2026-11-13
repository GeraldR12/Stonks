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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteCommandTest {

    private final UUID playerId = UUID.randomUUID();
    private Stonks plugin;
    private Player player;
    private Economy economy;
    private CompaniesService companies;
    private PlayersService players;
    private CompanyDao company;

    @BeforeEach
    void setUp() {
        plugin = mock(Stonks.class);
        player = mock(Player.class);
        economy = mock(Economy.class);
        companies = mock(CompaniesService.class);
        players = mock(PlayersService.class);
        Messages messages = mock(Messages.class);
        FileConfiguration config = mock(FileConfiguration.class);
        company = mock(CompanyDao.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("DeleteCommandTest"));
        when(plugin.getEconomy()).thenReturn(economy);
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getPlayersService()).thenReturn(players);
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isJournalReady()).thenReturn(true);
        try {
            File journalRoot = Files.createTempDirectory("stonks-delete-journal").toFile();
            MarketTransactionJournal journal = new MarketTransactionJournal(journalRoot);
            when(plugin.getTransactionJournal()).thenReturn(journal);
            MarketTransactionLocks.initialize(journal);
            assertTrue(MarketTransactionLocks.activate(plugin, journal));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        when(messages.getPluginPrefix()).thenReturn("");
        when(messages.getSoldActions()).thenReturn("Sold {0} for {1}");
        when(messages.getDeletedCompany()).thenReturn("Deleted {0}");
        when(player.getUniqueId()).thenReturn(playerId);
        when(companies.companyExists(1)).thenReturn(true);
        when(companies.getCompanyById(1)).thenReturn(company);
        when(company.getId()).thenReturn(1);
        when(company.getTotalShares()).thenReturn(10);
        when(company.getCurrentSharePrice()).thenReturn(2.0);
        when(company.getName()).thenReturn("Acme");
        when(players.hasSharesInCompany(playerId, 1, 10)).thenReturn(true);
        when(economy.depositPlayer(player, 20.0)).thenReturn(new EconomyResponse(20, 100, EconomyResponse.ResponseType.SUCCESS, null));
        when(players.removeSharesFromPlayer(playerId, 1, 10)).thenReturn(true);
        when(companies.deleteCompany(1)).thenReturn(true);
    }

@Test
    void revokedLifecyclePreventsDeleteEffects() {
        MarketTransactionLocks.revoke();

        new DeleteCommand(plugin).onCommand(player, new String[]{"1"});

        verify(economy, never()).depositPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
        verify(players, never()).removeSharesFromPlayer(any(), anyLong(), anyInt());
        verify(companies, never()).deleteCompany(anyLong());
        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
    }

@Test
    void concurrentRevocationAfterDeleteAdmissionCompletesSafely() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> commandResult = new AtomicReference<>();
        when(companies.getCompanyById(1)).thenAnswer(invocation -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (Throwable t) {
                failure.set(t);
                throw t;
            }
            return company;
        });

        Thread worker = new Thread(() -> {
            try {
                commandResult.set(new DeleteCommand(plugin).onCommand(player, new String[]{"1"}));
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
            verify(economy, times(1)).depositPlayer(player, 20.0);
            verify(companies, times(1)).deleteCompany(1);
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            release.countDown();
            if (revoker != null) revoker.join(5000);
            worker.join(5000);
        }
    }

    @Test
    void unavailableReadinessDoesNotMutateDelete() {
        when(plugin.isJournalReady()).thenReturn(false);

        new DeleteCommand(plugin).onCommand(player, new String[]{"1"});

        verify(economy, never()).depositPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
        verify(players, never()).removeSharesFromPlayer(any(), anyLong(), anyInt());
        verify(companies, never()).deleteCompany(anyLong());
    }

    @Test
    void readinessProbeFailureDoesNotMutateDelete() {
        when(plugin.isJournalReady()).thenThrow(new IllegalStateException("unavailable"));

        new DeleteCommand(plugin).onCommand(player, new String[]{"1"});

        verify(economy, never()).depositPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
        verify(players, never()).removeSharesFromPlayer(any(), anyLong(), anyInt());
        verify(companies, never()).deleteCompany(anyLong());
    }

    @Test
    void failedDepositDoesNotRemoveShares() {
        when(economy.depositPlayer(player, 20.0)).thenReturn(new EconomyResponse(0, 100, EconomyResponse.ResponseType.FAILURE, "failed"));
        new DeleteCommand(plugin).onCommand(player, new String[]{"1"});
        verify(players, never()).removeSharesFromPlayer(any(), anyLong(), anyInt());
        verify(companies, never()).deleteCompany(anyLong());
    }

    @Test
    void successfulDeletionDepositsOnceAndDeletes() {
        new DeleteCommand(plugin).onCommand(player, new String[]{"1"});
        verify(economy, times(1)).depositPlayer(player, 20.0);
        verify(players, times(1)).removeSharesFromPlayer(playerId, 1, 10);
        verify(companies, times(1)).deleteCompany(1);
    }

    @Test
    void deleteFailureDoesNotRepeatPayout() {
        when(companies.deleteCompany(1)).thenReturn(false);
        new DeleteCommand(plugin).onCommand(player, new String[]{"1"});
        verify(economy, times(1)).depositPlayer(player, 20.0);
        verify(companies, times(1)).deleteCompany(1);
    }
}
