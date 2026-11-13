package me.geraldr12.schedulers;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.QuoteDao;
import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.data.entities.QuoteEntity;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.services.PriceHistoryLog;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.data.sources.yml.implementations.CompaniesYml;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.SizedStack;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Up to 2.10.2 every timed update reversed the stored history; it must stay oldest first. */
class InterestRateSchedulerHistoryTest {

    @TempDir Path tempDir;

    @Test
    void repeatedUpdatesKeepHistoryOldestFirstAndWriteOneCsvLineEach() throws Exception {
        Stonks plugin = mock(Stonks.class);
        Server server = mock(Server.class);
        MarketTransactionJournal journal = mock(MarketTransactionJournal.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getBoolean("Stonks.StockCrash.Enabled", true)).thenReturn(false);
        when(plugin.getConfig()).thenReturn(config);
        Field serverField = JavaPlugin.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(plugin, server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        Path historyDir = tempDir.resolve("history");
        when(plugin.getPriceHistoryLog()).thenReturn(new PriceHistoryLog(historyDir, () -> true));

        Path data = tempDir.resolve("data");
        Files.createDirectories(data);
        CompaniesRepository repository = new CompaniesRepository(data.toFile(), new CompaniesYml(data.toFile()));
        SizedStack<QuoteDao> history = new SizedStack<>(500);
        history.push(new QuoteDao(0.0, 10.0, 1L));
        history.push(new QuoteDao(0.01, 10.1, 2L));
        assertTrue(repository.save(CompanyDao.builder()
                .id(3).name("Acme, Inc").description("d").icon(Material.STONE).risk(1)
                .initialSharePrice(10.0).currentSharePrice(10.1)
                .totalShares(100).availableShares(40)
                .historic(history).ownerUuid("owner").bankrupt(false).build().toEntity()));
        InterestRateScheduler scheduler = new InterestRateScheduler(plugin, new CompaniesService(plugin, repository),
                mock(SignsService.class), mock(PlayersService.class), mock(Messages.class));

        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
        try {
            for (int i = 0; i < 3; i++) scheduler.run();
        } finally {
            MarketTransactionLocks.revoke();
        }

        List<QuoteEntity> stored = repository.getById(3L).orElseThrow().getHistoric();
        assertEquals(5, stored.size());
        assertEquals(1L, stored.get(0).getTimestamp());
        assertEquals(2L, stored.get(1).getTimestamp());
        for (int i = 1; i < stored.size(); i++) {
            assertTrue(stored.get(i).getTimestamp() >= stored.get(i - 1).getTimestamp(), "oldest first at " + i);
        }
        CompanyEntity entity = repository.getById(3L).orElseThrow();
        assertEquals(entity.getCurrentSharePrice(), stored.get(stored.size() - 1).getPrice());

        List<String> csv = Files.readAllLines(historyDir.resolve("3.csv"));
        assertEquals(PriceHistoryLog.HEADER, csv.get(0));
        assertEquals(4, csv.size());
        assertTrue(csv.get(1).contains(",3,\"Acme, Inc\",1,update,,10.1,"), csv.get(1));
    }

    @Test
    void historySavedOutOfOrderLoadsSortedSoTheNewestIsOnTop() {
        CompanyEntity entity = new CompanyEntity();
        entity.setId(1);
        entity.setName("x");
        entity.setIcon(Material.STONE);
        entity.setHistoric(List.of(quote(30, 4.0), quote(10, 2.0), quote(40, 5.0), quote(20, 3.0)));
        CompanyDao company = (CompanyDao) new CompanyDao().fromEntity(entity);
        assertEquals(40, company.getHistoric().peek().getTimestamp());
        assertEquals(List.of(10L, 20L, 30L, 40L), company.getHistoric().stream().map(QuoteDao::getTimestamp).toList());
    }

    private static QuoteEntity quote(long timestamp, double price) {
        QuoteEntity q = new QuoteEntity();
        q.setTimestamp(timestamp);
        q.setPrice(price);
        q.setVariation(0);
        return q;
    }
}
