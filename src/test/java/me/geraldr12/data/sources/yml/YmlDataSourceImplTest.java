package me.geraldr12.data.sources.yml;

import me.geraldr12.data.entities.CompanyEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class YmlDataSourceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTrip() {
        YmlDataSourceImpl<CompanyEntity> source = new YmlDataSourceImpl<>(tempDir.toFile());
        CompanyEntity entity = company(1, "one");

        assertTrue(source.save("companies/1.yml", entity));
        CompanyEntity loaded = source.load("companies/1.yml", CompanyEntity.class);

        assertNotNull(loaded);
        assertEquals(1, loaded.getId());
        assertEquals("one", loaded.getName());
    }

    @Test
    void deleteReportsSuccess() {
        YmlDataSourceImpl<CompanyEntity> source = new YmlDataSourceImpl<>(tempDir.toFile());
        assertTrue(source.save("companies/1.yml", company(1, "one")));
        assertTrue(source.delete("companies/1.yml"));
        assertFalse(source.exists("companies/1.yml"));
        assertTrue(source.delete("companies/1.yml"));
    }

    @Test
    void invalidSaveIsObservable() {
        YmlDataSourceImpl<CompanyEntity> source = new YmlDataSourceImpl<>(tempDir.toFile());
        assertFalse(source.save(null, company(1, "one")));
        assertFalse(source.save("companies/1.yml", null));
    }

    @Test
    void twoSourcesShareRootLock() throws Exception {
        YmlDataSourceImpl<CompanyEntity> first = new YmlDataSourceImpl<>(tempDir.toFile());
        YmlDataSourceImpl<CompanyEntity> second = new YmlDataSourceImpl<>(tempDir.toFile());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<Boolean>> writes = List.of(
                () -> first.save("companies/1.yml", company(1, "first")),
                () -> second.save("companies/1.yml", company(1, "second"))
        );

        for (var result : executor.invokeAll(writes)) assertTrue(result.get());
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertNotNull(first.load("companies/1.yml", CompanyEntity.class));
    }

    @Test
    void concurrentWritesRemainLoadable() throws Exception {
        YmlDataSourceImpl<CompanyEntity> source = new YmlDataSourceImpl<>(tempDir.toFile());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Callable<Boolean>> writes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int id = i;
            writes.add(() -> source.save("companies/" + (id % 4) + ".yml", company(id, "company-" + id)));
        }
        for (var result : executor.invokeAll(writes)) assertTrue(result.get());
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        for (int i = 0; i < 4; i++) {
            assertNotNull(source.load("companies/" + i + ".yml", CompanyEntity.class));
        }
        try (var paths = Files.list(tempDir.resolve("companies"))) {
            assertEquals(0, paths
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count());
        }
    }

    private CompanyEntity company(int id, String name) {
        CompanyEntity entity = new CompanyEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setInitialSharePrice(10.0);
        entity.setCurrentSharePrice(10.0);
        entity.setTotalShares(100);
        entity.setAvailableShares(100);
        entity.setHistoric(new ArrayList<>());
        return entity;
    }
}
