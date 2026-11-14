package me.geraldr12.data.repositories.implementations;

import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.data.sources.yml.implementations.CompaniesYml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompaniesRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentAllocationsAcrossIndependentRepositoriesAreUniqueAndPersisted() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            Path root = Files.createTempDirectory("companies-id-concurrency");
            CompaniesRepository first = new CompaniesRepository(root.toFile(), new CompaniesYml(root.toFile()));
            CompaniesRepository second = new CompaniesRepository(root.toFile(), new CompaniesYml(root.toFile()));
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Long> firstId = executor.submit(() -> {
                    start.await();
                    return first.allocateNextId();
                });
                Future<Long> secondId = executor.submit(() -> {
                    start.await();
                    return second.allocateNextId();
                });
                start.countDown();
                Long allocatedFirst = firstId.get();
                Long allocatedSecond = secondId.get();
                assertNotNull(allocatedFirst);
                assertNotNull(allocatedSecond);
                assertNotEquals(allocatedFirst, allocatedSecond);
                assertEquals("nextId: 3", readCounter(root));
                assertEquals(3L, new CompaniesRepository(root.toFile(),
                        new CompaniesYml(root.toFile())).allocateNextId());
                assertEquals("nextId: 4", readCounter(root));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void allocationsThroughDirectoryAliasRemainUnique() throws Exception {
        Path root = Files.createTempDirectory("companies-id-alias");
        Path alias = root.resolve("alias");
        try {
            Files.createSymbolicLink(alias, root);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not supported");
        }
        CompaniesRepository first = new CompaniesRepository(root.toFile(), new CompaniesYml(root.toFile()));
        CompaniesRepository second = new CompaniesRepository(alias.toFile(), new CompaniesYml(alias.toFile()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Long> firstId = executor.submit(() -> {
                start.await();
                return first.allocateNextId();
            });
            Future<Long> secondId = executor.submit(() -> {
                start.await();
                return second.allocateNextId();
            });
            start.countDown();
            Long allocatedFirst = firstId.get();
            Long allocatedSecond = secondId.get();
            assertNotNull(allocatedFirst);
            assertNotNull(allocatedSecond);
            assertNotEquals(allocatedFirst, allocatedSecond);
            assertEquals("nextId: 3", readCounter(root));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingCounterRecoversAfterExistingCompanies() {
        CompaniesYml source = new CompaniesYml(tempDir.toFile());
        source.save("companies/4.yml", company(4));
        source.save("companies/12.yml", company(12));
        CompaniesRepository repository = new CompaniesRepository(tempDir.toFile(), source);

        assertEquals(13L, repository.allocateNextId());
        assertEquals("nextId: 14", readCounter().trim().replace("---", "").trim());
    }

    @Test
    void missingCounterStartsAtOneForEmptyInstall() {
        CompaniesRepository repository = new CompaniesRepository(
                tempDir.toFile(), new CompaniesYml(tempDir.toFile()));

        assertEquals(1L, repository.allocateNextId());
        assertEquals("nextId: 2", readCounter().trim().replace("---", "").trim());
    }

    @Test
    void malformedCounterFailsClosed() {
        CompaniesYml source = new CompaniesYml(tempDir.toFile());
        source.save("companies/7.yml", company(7));
        Path counter = tempDir.resolve("companies/data.yml");
        try {
            Files.createDirectories(counter.getParent());
            Files.writeString(counter, "nextId: not-a-number\n");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        CompaniesRepository repository = new CompaniesRepository(tempDir.toFile(), source);

        assertNull(repository.allocateNextId());
    }

    private String readCounter(Path root) {
        try {
            return Files.readString(root.resolve("companies/data.yml")).replace("---", "").trim();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String readCounter() {
        try {
            return Files.readString(tempDir.resolve("companies/data.yml"));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private CompanyEntity company(int id) {
        CompanyEntity company = new CompanyEntity();
        company.setId(id);
        company.setName("company-" + id);
        return company;
    }
}
