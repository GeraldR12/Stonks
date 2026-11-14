package me.geraldr12.schedulers;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.QuoteDao;
import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.data.sources.yml.implementations.CompaniesYml;
import me.geraldr12.events.CompanyStockUpdateEvent;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.SizedStack;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterestRateSchedulerSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultCompanyZeroUpdatesAndPersistsOnce() throws Exception {
        assertSchedulerSnapshot(0);
    }

    @Test
    void positiveCompanyUpdatesAndPersistsOnce() throws Exception {
        assertSchedulerSnapshot(7);
    }

    private void assertSchedulerSnapshot(int id) throws Exception {
        Stonks plugin = mock(Stonks.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        Logger logger = mock(Logger.class);
        SignsService signs = mock(SignsService.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getBoolean("Stonks.StockCrash.Enabled", true)).thenReturn(false);
        when(plugin.getConfig()).thenReturn(config);
        Field serverField = JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(plugin, server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);

        CompaniesRepository repository = spy(new CompaniesRepository(
                tempDir.toFile(), new CompaniesYml(tempDir.toFile())));
        SizedStack<QuoteDao> history = new SizedStack<>(500);
        history.push(new QuoteDao(0.0, 10.0, 1L));
        CompanyDao company = CompanyDao.builder()
                .id(id).name("default").description("snapshot")
                .icon(Material.STONE).risk(1)
                .initialSharePrice(10.0).currentSharePrice(10.0)
                .totalShares(100).availableShares(40)
                .historic(history).ownerUuid("owner").bankrupt(false).build();
        assertTrue(repository.save(company.toEntity()));
        clearInvocations(repository);
        CompaniesService service = spy(new CompaniesService(plugin, repository));
        InterestRateScheduler scheduler = new InterestRateScheduler(
                plugin, service, signs, mock(PlayersService.class), mock(Messages.class));

        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
        try {
            scheduler.run();

            ArgumentCaptor<CompanyEntity> saved = ArgumentCaptor.forClass(CompanyEntity.class);
            verify(repository, times(1)).save(saved.capture());
            verify(service, times(1)).persistSchedulerSnapshot(any());
            CompanyEntity persisted = repository.getById((long) id).orElseThrow();
            assertEquals(id, persisted.getId());
            assertEquals(2, persisted.getHistoric().size());
            assertEquals(saved.getValue().getCurrentSharePrice(), persisted.getCurrentSharePrice());
            assertTrue(Double.isFinite(persisted.getCurrentSharePrice()));
            assertTrue(persisted.getCurrentSharePrice() > 0);
            assertEquals(persisted.getCurrentSharePrice(), persisted.getHistoric().get(1).getPrice());
            assertEquals("default", persisted.getName());
            assertEquals("snapshot", persisted.getDescription());
            assertEquals(Material.STONE, persisted.getIcon());
            assertEquals(100, persisted.getTotalShares());
            assertEquals(40, persisted.getAvailableShares());
            assertEquals("owner", persisted.getOwnerUuid());
            verify(pluginManager, times(1)).callEvent(any(CompanyStockUpdateEvent.class));
            verify(signs, times(1)).updateBukkitSignsByCompany(id);
            verify(logger, never()).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            MarketTransactionLocks.revoke();
        }
    }
}
